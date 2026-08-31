package io.github.susongyan.bobastraw.internal;

import io.github.susongyan.bobastraw.BobaStrawConnectionException;
import io.github.susongyan.bobastraw.ProtocolVersion;
import io.github.susongyan.bobastraw.protocol.RespCodec;
import io.github.susongyan.bobastraw.protocol.RespValue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.List;
import java.util.ArrayList;
import java.util.function.Consumer;

/** One non-blocking TCP connection with FIFO response matching. */
public final class NioConnection implements AutoCloseable {
    private static final int READ_BUFFER_SIZE = 8192;

    private final String host;
    private final int port;
    private final Duration timeout;
    private final ProtocolVersion requestedProtocol;
    private final String username;
    private final String password;
    private final String clientName;
    private final Duration idlePingInterval;
    private final Queue<Request> outbound = new ArrayDeque<Request>();
    private final Queue<Request> pending = new ArrayDeque<Request>();
    private final RespCodec.Decoder decoder = new RespCodec.Decoder();
    private final Object lock = new Object();
    private final CompletableFuture<Void> ready = new CompletableFuture<Void>();
    private final Consumer<RespValue> pushListener;

    private volatile boolean closed;
    private volatile long lastActivityNanos = System.nanoTime();
    private boolean healthCheckInFlight;
    private Selector selector;
    private SocketChannel channel;

    public NioConnection(
        String host,
        int port,
        Duration timeout,
        ProtocolVersion requestedProtocol,
        String username,
        String password,
        String clientName
    ) {
        this(host, port, timeout, requestedProtocol, username, password, clientName, null, Duration.ZERO);
    }

    public NioConnection(
        String host,
        int port,
        Duration timeout,
        ProtocolVersion requestedProtocol,
        String username,
        String password,
        String clientName,
        Consumer<RespValue> pushListener
    ) {
        this(host, port, timeout, requestedProtocol, username, password, clientName,
            pushListener, Duration.ZERO);
    }

    public NioConnection(
        String host,
        int port,
        Duration timeout,
        ProtocolVersion requestedProtocol,
        String username,
        String password,
        String clientName,
        Consumer<RespValue> pushListener,
        Duration idlePingInterval
    ) {
        this.host = host;
        this.port = port;
        this.timeout = timeout;
        this.requestedProtocol = requestedProtocol;
        this.username = username;
        this.password = password;
        this.clientName = clientName;
        this.pushListener = pushListener;
        this.idlePingInterval = idlePingInterval == null ? Duration.ZERO : idlePingInterval;

        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                eventLoop();
            }
        }, "boba-straw-nio");
        thread.setDaemon(true);
        thread.start();
    }

    public CompletionStage<RespValue> execute(String[] command) {
        final java.util.concurrent.atomic.AtomicReference<Request> requestRef =
            new java.util.concurrent.atomic.AtomicReference<Request>();
        final CompletableFuture<RespValue> result = new CompletableFuture<RespValue>();
        ready.whenComplete((ignored, error) -> {
            if (error != null) {
                result.completeExceptionally(error);
                return;
            }
            Request request = enqueue(command);
            requestRef.set(request);
            request.future.whenComplete((value, requestError) -> {
                if (requestError == null) {
                    result.complete(value);
                } else {
                    result.completeExceptionally(requestError);
                }
            });
        });
        result.whenComplete((value, error) -> {
            if (result.isCancelled()) {
                Request request = requestRef.get();
                if (request != null) {
                    cancel(request);
                }
            }
        });
        return result;
    }

    public CompletionStage<RespValue> execute(byte[][] command) {
        final java.util.concurrent.atomic.AtomicReference<Request> requestRef =
            new java.util.concurrent.atomic.AtomicReference<Request>();
        final CompletableFuture<RespValue> result = new CompletableFuture<RespValue>();
        ready.whenComplete((ignored, error) -> {
            if (error != null) {
                result.completeExceptionally(error);
                return;
            }
            Request request = enqueue(command);
            requestRef.set(request);
            request.future.whenComplete((value, requestError) -> {
                if (requestError == null) {
                    result.complete(value);
                } else {
                    result.completeExceptionally(requestError);
                }
            });
        });
        result.whenComplete((value, error) -> {
            if (result.isCancelled() && requestRef.get() != null) {
                cancel(requestRef.get());
            }
        });
        return result;
    }

    public CompletionStage<List<RespValue>> executeBatch(List<String[]> commands) {
        return ready.thenCompose(ignored -> {
            List<CompletableFuture<RespValue>> futures = new ArrayList<CompletableFuture<RespValue>>();
            synchronized (lock) {
                if (closed) {
                    CompletableFuture<List<RespValue>> failed = new CompletableFuture<List<RespValue>>();
                    failed.completeExceptionally(new BobaStrawConnectionException("Client is closed"));
                    return failed;
                }
                for (String[] command : commands) {
                    Request request = new Request(ByteBuffer.wrap(RespCodec.encodeCommand(command)));
                    outbound.add(request);
                    futures.add(request.future);
                }
                if (selector != null) {
                    selector.wakeup();
                }
            }
            CompletableFuture<?>[] all = futures.toArray(new CompletableFuture<?>[futures.size()]);
            CompletableFuture<List<RespValue>> aggregate = CompletableFuture.allOf(all).thenApply(ignoredAgain -> {
                List<RespValue> values = new ArrayList<RespValue>(futures.size());
                for (CompletableFuture<RespValue> future : futures) {
                    values.add(future.join());
                }
                return values;
            });
            aggregate.whenComplete((value, error) -> {
                if (aggregate.isCancelled()) {
                    for (CompletableFuture<RespValue> future : futures) {
                        future.cancel(false);
                    }
                }
            });
            return aggregate;
        });
    }

    public String host() {
        return host;
    }

    public int port() {
        return port;
    }

    public ProtocolVersion protocol() {
        return requestedProtocol;
    }

    public String username() {
        return username;
    }

    public String password() {
        return password;
    }

    public String clientName() {
        return clientName;
    }

    public boolean isOpen() {
        return !closed;
    }

    public Duration idlePingInterval() {
        return idlePingInterval;
    }

    private CompletionStage<RespValue> executeConnected(String[] command) {
        return enqueue(command).future;
    }

    private Request enqueue(String[] command) {
        return enqueue(RespCodec.encodeCommand(command));
    }

    private Request enqueue(byte[][] command) {
        return enqueue(RespCodec.encodeCommand(command));
    }

    private Request enqueue(byte[] encoded) {
        Request request = new Request(ByteBuffer.wrap(encoded));
        synchronized (lock) {
            if (closed) {
                request.future.completeExceptionally(new BobaStrawConnectionException("Client is closed"));
                return request;
            }
            outbound.add(request);
            if (selector != null) {
                selector.wakeup();
            }
        }
        return request;
    }

    private void cancel(Request request) {
        synchronized (lock) {
            if (outbound.remove(request)) {
                request.future.completeExceptionally(new java.util.concurrent.CancellationException());
            } else {
                request.cancelled = true;
            }
        }
    }

    private void eventLoop() {
        try {
            selector = Selector.open();
            channel = SocketChannel.open();
            channel.configureBlocking(false);
            channel.connect(new InetSocketAddress(host, port));
            channel.register(selector, SelectionKey.OP_CONNECT);

            while (!closed) {
                selector.select(100);
                processSelectedKeys();
                armWrites();
                checkIdle();
            }
        } catch (Throwable error) {
            failAll(error);
        } finally {
            closeResources();
        }
    }

    private void processSelectedKeys() throws IOException {
        Iterator<SelectionKey> iterator = selector.selectedKeys().iterator();
        while (iterator.hasNext()) {
            SelectionKey key = iterator.next();
            iterator.remove();
            if (!key.isValid()) {
                continue;
            }
            if (key.isConnectable()) {
                connect(key);
            }
            if (key.isWritable()) {
                write(key);
            }
            if (key.isReadable()) {
                read();
            }
        }
    }

    private void connect(SelectionKey key) throws IOException {
        if (channel.finishConnect()) {
            key.interestOps(SelectionKey.OP_READ);
            startHandshake();
        }
    }

    private void startHandshake() {
        if (requestedProtocol == ProtocolVersion.RESP2) {
            authenticateResp2();
            return;
        }

        String[] hello = helloCommand();
        executeConnected(hello).whenComplete((response, error) -> {
            if (error == null) {
                ready.complete(null);
                return;
            }

            if (requestedProtocol == ProtocolVersion.AUTO && isUnknownHello(error)) {
                authenticateResp2();
                return;
            }
            ready.completeExceptionally(error);
            close();
        });
    }

    private String[] helloCommand() {
        if (password == null) {
            return new String[] { "HELLO", "3" };
        }
        String authUser = username == null || username.isEmpty() ? "default" : username;
        if (clientName == null || clientName.isEmpty()) {
            return new String[] { "HELLO", "3", "AUTH", authUser, password };
        }
        return new String[] { "HELLO", "3", "AUTH", authUser, password, "SETNAME", clientName };
    }

    private void authenticateResp2() {
        if (password == null) {
            setClientName();
            return;
        }
        String[] auth = username == null || username.isEmpty()
            ? new String[] { "AUTH", password }
            : new String[] { "AUTH", username, password };
        executeConnected(auth).whenComplete((response, error) -> {
            if (error != null) {
                ready.completeExceptionally(error);
                close();
                return;
            }
            setClientName();
        });
    }

    private void setClientName() {
        if (clientName == null || clientName.isEmpty()) {
            ready.complete(null);
            return;
        }
        executeConnected(new String[] { "CLIENT", "SETNAME", clientName }).whenComplete((response, error) -> {
            if (error == null) {
                ready.complete(null);
            } else {
                ready.completeExceptionally(error);
                close();
            }
        });
    }

    private boolean isUnknownHello(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        if (message == null) {
            return false;
        }
        String normalized = message.toLowerCase(java.util.Locale.ROOT);
        return normalized.contains("unknown command") && normalized.contains("hello");
    }

    private void armWrites() {
        if (channel == null || !channel.isConnected()) {
            return;
        }
        synchronized (lock) {
            SelectionKey key = channel.keyFor(selector);
            if (key != null && key.isValid() && !outbound.isEmpty()) {
                key.interestOps(key.interestOps() | SelectionKey.OP_WRITE);
            }
        }
    }

    private void write(SelectionKey key) throws IOException {
        while (true) {
            Request request;
            synchronized (lock) {
                request = outbound.peek();
            }
            if (request == null) {
                key.interestOps(key.interestOps() & ~SelectionKey.OP_WRITE);
                return;
            }

            channel.write(request.buffer);
            lastActivityNanos = System.nanoTime();
            if (request.buffer.hasRemaining()) {
                return;
            }
            synchronized (lock) {
                outbound.remove();
                pending.add(request);
            }
        }
    }

    private void read() throws IOException {
        ByteBuffer readBuffer = ByteBuffer.allocate(READ_BUFFER_SIZE);
        int count = channel.read(readBuffer);
        if (count == -1) {
            throw new IOException("Redis closed the connection");
        }
        if (count == 0) {
            return;
        }
        lastActivityNanos = System.nanoTime();

        decoder.feed(readBuffer.array(), count);
        RespValue value;
        while ((value = decoder.poll()) != null) {
            if (value instanceof RespValue.Push) {
                if (pushListener != null) {
                    pushListener.accept(value);
                }
                continue;
            }
            if (pushListener != null && isPubSubMessage(value)) {
                pushListener.accept(value);
                continue;
            }
            completeNextRequest(value);
        }
    }

    private boolean isPubSubMessage(RespValue value) {
        if (!(value instanceof RespValue.Array)) {
            return false;
        }
        java.util.List<RespValue> values = ((RespValue.Array) value).values;
        if (values.isEmpty()) {
            return false;
        }
        String type = values.get(0).asString();
        return "message".equals(type) || "pmessage".equals(type);
    }

    private void completeNextRequest(RespValue value) throws IOException {
        if (value instanceof RespValue.Attribute) {
            value = ((RespValue.Attribute) value).value;
        }
        Request request;
        synchronized (lock) {
            request = pending.poll();
        }
        if (request == null) {
            throw new IOException("Received an unsolicited Redis response");
        }
        if (request.cancelled) {
            return;
        }
        if (value instanceof RespValue.Error) {
            request.future.completeExceptionally(
                new BobaStrawConnectionException(((RespValue.Error) value).message)
            );
        } else {
            request.future.complete(value);
        }
    }

    private void checkIdle() {
        if (idlePingInterval.isZero() || idlePingInterval.isNegative() || closed) {
            return;
        }
        synchronized (lock) {
            if (healthCheckInFlight || !outbound.isEmpty() || !pending.isEmpty()
                || System.nanoTime() - lastActivityNanos < idlePingInterval.toNanos()) {
                return;
            }
            healthCheckInFlight = true;
        }
        executeConnected(new String[] { "PING" }).whenComplete((value, error) -> {
            synchronized (lock) {
                healthCheckInFlight = false;
                lastActivityNanos = System.nanoTime();
            }
            if (error != null) {
                close();
            }
        });
    }

    private void failAll(Throwable cause) {
        BobaStrawConnectionException error = cause instanceof BobaStrawConnectionException
            ? (BobaStrawConnectionException) cause
            : new BobaStrawConnectionException("Redis connection failed", cause);

        synchronized (lock) {
            closed = true;
            ready.completeExceptionally(error);
            for (Request request : outbound) {
                request.future.completeExceptionally(error);
            }
            for (Request request : pending) {
                request.future.completeExceptionally(error);
            }
            outbound.clear();
            pending.clear();
        }
    }

    @Override
    public void close() {
        failAll(new BobaStrawConnectionException("Client closed"));
        if (selector != null) {
            selector.wakeup();
        }
    }

    private void closeResources() {
        try {
            if (channel != null) {
                channel.close();
            }
        } catch (IOException ignored) {
            // Closing is best-effort.
        }
        try {
            if (selector != null) {
                selector.close();
            }
        } catch (IOException ignored) {
            // Closing is best-effort.
        }
    }

    private static final class Request {
        private final ByteBuffer buffer;
        private final CompletableFuture<RespValue> future = new CompletableFuture<RespValue>();
        private volatile boolean cancelled;

        private Request(ByteBuffer buffer) {
            this.buffer = buffer;
        }
    }
}
