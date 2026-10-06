package io.github.susongyan.bobastraw.internal;

import io.github.susongyan.bobastraw.BobaStrawConnectionException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.function.Consumer;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLException;

/** EventLoop-owned TLS records. Does not know Redis commands or perform retries. */
final class NioTlsTransport {
    static final int MAX_BUFFER_BYTES = 256 * 1024;
    private static final ByteBuffer EMPTY = ByteBuffer.allocate(0).asReadOnlyBuffer();
    private final SSLEngine engine;
    private final SocketChannel channel;
    private final NioEventLoop loop;
    private final ExecutorService executor;
    private final Consumer<Throwable> failure;
    private ByteBuffer input;
    private ByteBuffer output;
    private ByteBuffer plaintext;
    private boolean underflow = true;
    private boolean handshaken;
    private boolean tasksRunning;
    private volatile boolean closed;
    private Future<?> task;
    private int readBudget;
    private int writeBudget;
    private int engineBudget;
    long readOperations;
    long bytesRead;
    long writeOperations;
    long bytesWritten;

    NioTlsTransport(SSLEngine engine, SocketChannel channel, NioEventLoop loop,
        ExecutorService executor, Consumer<Throwable> failure) throws SSLException {
        this.engine = engine;
        this.channel = channel;
        this.loop = loop;
        this.executor = executor;
        this.failure = failure;
        resetBudget();
        input = allocate(engine.getSession().getPacketBufferSize());
        output = allocate(engine.getSession().getPacketBufferSize());
        output.flip();
        plaintext = allocate(engine.getSession().getApplicationBufferSize());
        engine.beginHandshake();
    }

    boolean isHandshaken() {
        return handshaken;
    }

    void resetBudget() {
        readBudget = loop.ioLimits().maxReadBytesPerTurn;
        writeBudget = loop.ioLimits().maxWriteBytesPerTurn;
        engineBudget = 64;
    }

    boolean tasksRunning() {
        return tasksRunning;
    }

    boolean wantsWrite() {
        return !tasksRunning && (output.hasRemaining()
            || engine.getHandshakeStatus() == SSLEngineResult.HandshakeStatus.NEED_WRAP);
    }

    boolean canWriteApplication() {
        return !tasksRunning && handshaken
            && engine.getHandshakeStatus() == SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING;
    }

    boolean hasBufferedInput() {
        return !tasksRunning && (plaintext.position() > 0 || (!underflow && input.position() > 0)
            || "NEED_UNWRAP_AGAIN".equals(engine.getHandshakeStatus().name()));
    }

    /** Bounded handshake/post-handshake progress; every socket operation is nonblocking. */
    void progress() throws IOException {
        if (closed || tasksRunning) {
            return;
        }
        for (int step = 0; step < 4; step++) {
            flush();
            if (output.hasRemaining()) {
                return;
            }
            SSLEngineResult.HandshakeStatus status = engine.getHandshakeStatus();
            switch (status) {
                case NEED_TASK:
                    delegateTasks();
                    return;
                case NEED_WRAP:
                    wrap(new ByteBuffer[] {EMPTY.duplicate()}, 1);
                    break;
                case NEED_UNWRAP:
                    if (plaintext.position() > 0 || !unwrap()) {
                        return;
                    }
                    break;
                case FINISHED:
                case NOT_HANDSHAKING:
                    handshaken = true;
                    return;
                default:
                    // Java 9+ NEED_UNWRAP_AGAIN without a Java 9 API dependency.
                    if (!"NEED_UNWRAP_AGAIN".equals(status.name()) || !unwrap()) {
                        return;
                    }
            }
        }
    }

    long write(ByteBuffer[] sources, int count) throws IOException {
        progress();
        if (closed || tasksRunning || !handshaken || output.hasRemaining()
            || engine.getHandshakeStatus() != SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING) {
            return 0L;
        }
        int consumed = wrap(sources, count);
        flush();
        return consumed;
    }

    int read(ByteBuffer target) throws IOException {
        progress();
        if (closed || tasksRunning || !handshaken) {
            return 0;
        }
        if (plaintext.position() == 0) {
            SSLEngineResult.HandshakeStatus status = engine.getHandshakeStatus();
            if (status == SSLEngineResult.HandshakeStatus.NEED_WRAP
                || status == SSLEngineResult.HandshakeStatus.NEED_TASK) {
                return 0;
            }
            unwrap();
        }
        plaintext.flip();
        int count = Math.min(target.remaining(), plaintext.remaining());
        int limit = plaintext.limit();
        plaintext.limit(plaintext.position() + count);
        target.put(plaintext);
        plaintext.limit(limit);
        plaintext.compact();
        return count;
    }

    private int wrap(ByteBuffer[] sources, int count) throws IOException {
        if (engineBudget == 0) {
            return 0;
        }
        engineBudget--;
        SSLEngineResult.HandshakeStatus before = engine.getHandshakeStatus();
        output.clear();
        SSLEngineResult result = engine.wrap(sources, 0, count, output);
        while (result.getStatus() == SSLEngineResult.Status.BUFFER_OVERFLOW) {
            output = grow(output, engine.getSession().getPacketBufferSize());
            result = engine.wrap(sources, 0, count, output);
        }
        output.flip();
        check(result);
        if (result.bytesConsumed() == 0 && result.bytesProduced() == 0
            && before == SSLEngineResult.HandshakeStatus.NEED_WRAP
            && result.getHandshakeStatus() == before) {
            throw new SSLException("TLS engine made no handshake write progress");
        }
        return result.bytesConsumed();
    }

    private boolean unwrap() throws IOException {
        if (engineBudget == 0) {
            return false;
        }
        engineBudget--;
        boolean again = "NEED_UNWRAP_AGAIN".equals(engine.getHandshakeStatus().name());
        if (!again && (underflow || input.position() == 0)) {
            if (!input.hasRemaining()) {
                input = grow(input, engine.getSession().getPacketBufferSize());
            }
            if (readBudget == 0) {
                return false;
            }
            int previousLimit = input.limit();
            input.limit(input.position() + Math.min(input.remaining(), readBudget));
            int received;
            try {
                received = channel.read(input);
            } finally {
                input.limit(previousLimit);
            }
            if (received < 0) {
                engine.closeInbound();
                throw new SSLException("TLS peer closed before Redis response completion");
            }
            if (received == 0) {
                return false;
            }
            readOperations++;
            bytesRead += received;
            readBudget -= received;
            underflow = false;
        }
        SSLEngineResult.HandshakeStatus before = engine.getHandshakeStatus();
        input.flip();
        SSLEngineResult result;
        try {
            result = engine.unwrap(input, plaintext);
            while (result.getStatus() == SSLEngineResult.Status.BUFFER_OVERFLOW) {
                plaintext = grow(plaintext, engine.getSession().getApplicationBufferSize());
                result = engine.unwrap(input, plaintext);
            }
        } finally {
            input.compact();
        }
        check(result);
        underflow = result.getStatus() == SSLEngineResult.Status.BUFFER_UNDERFLOW;
        if (!underflow && result.bytesConsumed() == 0 && result.bytesProduced() == 0
            && result.getHandshakeStatus() == before) {
            throw new SSLException("TLS engine made no read progress");
        }
        return !underflow && (result.bytesConsumed() > 0 || result.bytesProduced() > 0
            || result.getHandshakeStatus() == SSLEngineResult.HandshakeStatus.NEED_TASK);
    }

    private void check(SSLEngineResult result) throws SSLException {
        if (result.getStatus() == SSLEngineResult.Status.CLOSED) {
            throw new SSLException("TLS peer closed the connection");
        }
        if (result.getHandshakeStatus() == SSLEngineResult.HandshakeStatus.FINISHED) {
            handshaken = true;
        }
    }

    private void flush() throws IOException {
        if (output.hasRemaining() && writeBudget > 0) {
            int previousLimit = output.limit();
            output.limit(output.position() + Math.min(output.remaining(), writeBudget));
            int written;
            try {
                written = channel.write(output);
            } finally {
                output.limit(previousLimit);
            }
            if (written > 0) {
                writeOperations++;
                bytesWritten += written;
                writeBudget -= written;
            }
        }
    }

    private void delegateTasks() throws SSLException {
        final List<Runnable> tasks = new ArrayList<Runnable>();
        Runnable next;
        while ((next = engine.getDelegatedTask()) != null) {
            tasks.add(next);
        }
        if (tasks.isEmpty()) {
            throw new SSLException("TLS engine requested a task but supplied none");
        }
        tasksRunning = true;
        try {
            task = executor.submit(() -> {
                Throwable problem = null;
                try {
                    for (Runnable action : tasks) {
                        if (closed) {
                            return;
                        }
                        action.run();
                    }
                } catch (Throwable error) {
                    problem = error;
                }
                final Throwable completedError = problem;
                loop.execute(new NioEventLoop.Task() {
                    @Override
                    public void run() {
                        if (closed) {
                            return;
                        }
                        tasksRunning = false;
                        task = null;
                        if (completedError != null) {
                            failure.accept(completedError);
                        }
                    }

                    @Override
                    public void reject(BobaStrawConnectionException error) {
                        // Resource owner has already closed the connection.
                    }
                });
            });
        } catch (RuntimeException error) {
            tasksRunning = false;
            throw new SSLException("TLS task capacity unavailable", error);
        }
    }

    /** Never wait for the peer or mutate the engine while a delegated task is still running. */
    void close() {
        closed = true;
        if (task != null) {
            task.cancel(true);
            if (executor instanceof ThreadPoolExecutor && task instanceof Runnable) {
                ((ThreadPoolExecutor) executor).remove((Runnable) task);
            }
        }
        if (!tasksRunning && !output.hasRemaining()) {
            try {
                engine.closeOutbound();
                output.clear();
                engine.wrap(EMPTY.duplicate(), output);
                output.flip();
                flush();
            } catch (Exception ignored) {
                // The owning connection closes its socket unconditionally.
            }
        }
    }

    private static ByteBuffer allocate(int size) throws SSLException {
        if (size <= 0 || size > MAX_BUFFER_BYTES) {
            throw new SSLException("TLS buffer limit exceeded");
        }
        return ByteBuffer.allocate(size);
    }

    private static ByteBuffer grow(ByteBuffer buffer, int required) throws SSLException {
        int size = Math.max(required, buffer.capacity() * 2);
        ByteBuffer replacement = allocate(size);
        buffer.flip();
        replacement.put(buffer);
        return replacement;
    }
}
