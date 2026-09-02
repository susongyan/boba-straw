package io.github.susongyan.bobastraw.internal;

import io.github.susongyan.bobastraw.BobaStrawConnectionException;
import io.github.susongyan.bobastraw.BobaStrawCommandMayHaveExecutedException;
import io.github.susongyan.bobastraw.BobaStrawCommandNotSentException;
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
import java.util.concurrent.ConcurrentLinkedQueue;
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
    private final Queue<Request> preReady = new ArrayDeque<Request>();
    private final Queue<ConnectionTask> submitted = new ConcurrentLinkedQueue<ConnectionTask>();
    private final RespCodec.Decoder decoder = new RespCodec.Decoder();
    private final CompletableFuture<Void> ready = new CompletableFuture<Void>();
    private final Consumer<RespValue> pushListener;

    private volatile boolean closed;
    private volatile boolean closeRequested;
    private volatile long lastActivityNanos = System.nanoTime();
    private boolean healthCheckInFlight;
    private long healthCheckDeadlineNanos;
    private boolean readyForCommands;
    private volatile BobaStrawConnectionException terminalError;
    private volatile Selector selector;
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
        final Request request = enqueueExternal(command);
        final CompletableFuture<RespValue> result = new CompletableFuture<RespValue>();
        request.future.whenComplete((value, requestError) -> {
            if (requestError == null) {
                result.complete(value);
            } else {
                result.completeExceptionally(requestError);
            }
        });
        result.whenComplete((value, error) -> {
            if (result.isCancelled()) {
                cancel(request);
            }
        });
        return result;
    }

    public CompletionStage<RespValue> execute(byte[][] command) {
        final Request request = enqueueExternal(command);
        final CompletableFuture<RespValue> result = new CompletableFuture<RespValue>();
        request.future.whenComplete((value, requestError) -> {
            if (requestError == null) {
                result.complete(value);
            } else {
                result.completeExceptionally(requestError);
            }
        });
        result.whenComplete((value, error) -> {
            if (result.isCancelled()) {
                cancel(request);
            }
        });
        return result;
    }

    public CompletionStage<List<RespValue>> executeBatch(List<String[]> commands) {
        List<CompletableFuture<RespValue>> futures = new ArrayList<CompletableFuture<RespValue>>();
        final List<Request> requests = new ArrayList<Request>(commands.size());
        for (String[] command : commands) {
            Request request = new Request(ByteBuffer.wrap(RespCodec.encodeCommand(command)));
            requests.add(request);
            futures.add(request.future);
        }
        enqueueExternal(requests);
        CompletableFuture<?>[] all = futures.toArray(new CompletableFuture<?>[futures.size()]);
        CompletableFuture<List<RespValue>> aggregate = CompletableFuture.allOf(all).thenApply(ignored -> {
            List<RespValue> values = new ArrayList<RespValue>(futures.size());
            for (CompletableFuture<RespValue> future : futures) {
                values.add(future.join());
            }
            return values;
        });
        aggregate.whenComplete((value, error) -> {
            if (aggregate.isCancelled()) {
                for (Request request : requests) {
                    cancel(request);
                }
            }
        });
        return aggregate;
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
        return !closed && !closeRequested;
    }

    public Duration idlePingInterval() {
        return idlePingInterval;
    }

    private CompletionStage<RespValue> executeConnected(String[] command) {
        return enqueueConnected(command).future;
    }

    private Request enqueueExternal(String[] command) {
        return enqueueExternal(RespCodec.encodeCommand(command));
    }

    private Request enqueueExternal(byte[][] command) {
        return enqueueExternal(RespCodec.encodeCommand(command));
    }

    private Request enqueueExternal(byte[] encoded) {
        final Request request = new Request(ByteBuffer.wrap(encoded));
        submit(new ConnectionTask() {
            @Override
            public void run() {
                if (readyForCommands) {
                    outbound.add(request);
                } else {
                    preReady.add(request);
                }
            }

            @Override
            public void fail(BobaStrawConnectionException error) {
                request.future.completeExceptionally(notSentFailure(error));
            }
        });
        return request;
    }

    private void enqueueExternal(final List<Request> requests) {
        submit(new ConnectionTask() {
            @Override
            public void run() {
                if (readyForCommands) {
                    outbound.addAll(requests);
                } else {
                    preReady.addAll(requests);
                }
            }

            @Override
            public void fail(BobaStrawConnectionException error) {
                for (Request request : requests) {
                    request.future.completeExceptionally(notSentFailure(error));
                }
            }
        });
    }

    private Request enqueueConnected(String[] command) {
        return enqueueConnected(RespCodec.encodeCommand(command));
    }

    private Request enqueueConnected(byte[] encoded) {
        final Request request = new Request(ByteBuffer.wrap(encoded));
        submit(new ConnectionTask() {
            @Override
            public void run() {
                outbound.add(request);
            }

            @Override
            public void fail(BobaStrawConnectionException error) {
                request.future.completeExceptionally(error);
            }
        });
        return request;
    }

    private void cancel(final Request request) {
        submit(new ConnectionTask() {
            @Override
            public void run() {
                cancelOnEventLoop(request);
            }

            @Override
            public void fail(BobaStrawConnectionException error) {
                request.future.completeExceptionally(error);
            }
        });
    }

    private void eventLoop() {
        try {
            selector = Selector.open();
            channel = SocketChannel.open();
            channel.configureBlocking(false);
            channel.connect(new InetSocketAddress(host, port));
            channel.register(selector, SelectionKey.OP_CONNECT);

            while (!closed) {
                drainSubmitted();
                if (closed) {
                    break;
                }
                selector.select(100);
                drainSubmitted();
                if (closed) {
                    break;
                }
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

    private void submit(ConnectionTask task) {
        BobaStrawConnectionException error = terminalError;
        if (error != null) {
            task.fail(error);
            return;
        }
        if (closeRequested) {
            error = terminalError;
            task.fail(error == null ? new BobaStrawConnectionException("Client is closed") : error);
            return;
        }
        submitted.add(task);
        error = terminalError;
        if (error != null && submitted.remove(task)) {
            task.fail(error);
            return;
        }
        Selector current = selector;
        if (current != null) {
            current.wakeup();
        }
    }

    private void drainSubmitted() {
        ConnectionTask task;
        while ((task = submitted.poll()) != null) {
            if (closed) {
                task.fail(terminalError);
            } else {
                task.run();
            }
        }
    }

    private void cancelOnEventLoop(Request request) {
        if (request.state == RequestState.QUEUED) {
            if (outbound.remove(request) || preReady.remove(request)) {
                request.state = RequestState.CANCELLED;
                request.future.completeExceptionally(new java.util.concurrent.CancellationException());
            }
            return;
        }
        if (request.state == RequestState.WRITING || request.state == RequestState.SENT) {
            request.state = RequestState.CANCELLED_DRAINING;
            request.future.completeExceptionally(new java.util.concurrent.CancellationException());
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
                activateUserCommands();
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
            activateUserCommands();
            return;
        }
        executeConnected(new String[] { "CLIENT", "SETNAME", clientName }).whenComplete((response, error) -> {
            if (error == null) {
                activateUserCommands();
            } else {
                ready.completeExceptionally(error);
                close();
            }
        });
    }

    private void activateUserCommands() {
        readyForCommands = true;
        while (!preReady.isEmpty()) {
            outbound.add(preReady.remove());
        }
        ready.complete(null);
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
        SelectionKey key = channel.keyFor(selector);
        if (key != null && key.isValid() && !outbound.isEmpty()) {
            key.interestOps(key.interestOps() | SelectionKey.OP_WRITE);
        }
    }

    private void write(SelectionKey key) throws IOException {
        while (true) {
            Request request = outbound.peek();
            if (request == null) {
                key.interestOps(key.interestOps() & ~SelectionKey.OP_WRITE);
                return;
            }

            if (request.state == RequestState.QUEUED) {
                request.state = RequestState.WRITING;
            }
            int written = channel.write(request.buffer);
            if (written > 0) {
                request.bytesWritten = true;
            }
            lastActivityNanos = System.nanoTime();
            if (request.buffer.hasRemaining()) {
                return;
            }
            outbound.remove();
            pending.add(request);
            if (request.state != RequestState.CANCELLED_DRAINING) {
                request.state = RequestState.SENT;
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
            RespValue payload = value instanceof RespValue.Attribute
                ? ((RespValue.Attribute) value).value
                : value;
            if (payload instanceof RespValue.Push) {
                if (pushListener != null) {
                    if (isPubSubAcknowledgement(payload)) {
                        completeNextRequest(payload);
                    } else {
                        pushListener.accept(payload);
                    }
                }
                continue;
            }
            if (pushListener != null && isPubSubMessage(payload)) {
                pushListener.accept(payload);
                continue;
            }
            completeNextRequest(value);
        }
    }

    private boolean isPubSubMessage(RespValue value) {
        String type = pubSubType(value);
        return "message".equals(type) || "pmessage".equals(type);
    }

    private boolean isPubSubAcknowledgement(RespValue value) {
        String type = pubSubType(value);
        return "subscribe".equals(type) || "psubscribe".equals(type)
            || "unsubscribe".equals(type) || "punsubscribe".equals(type)
            || "ssubscribe".equals(type) || "sunsubscribe".equals(type);
    }

    private String pubSubType(RespValue value) {
        java.util.List<RespValue> values;
        if (value instanceof RespValue.Array) {
            values = ((RespValue.Array) value).values;
        } else if (value instanceof RespValue.Push) {
            values = ((RespValue.Push) value).values;
        } else {
            return null;
        }
        if (values.isEmpty()) {
            return null;
        }
        return values.get(0).asString();
    }

    private void completeNextRequest(RespValue value) throws IOException {
        if (value instanceof RespValue.Attribute) {
            value = ((RespValue.Attribute) value).value;
        }
        Request request = pending.poll();
        if (request == null) {
            throw new IOException("Received an unsolicited Redis response");
        }
        if (request.state == RequestState.CANCELLED_DRAINING) {
            request.state = RequestState.COMPLETED;
            return;
        }
        request.state = RequestState.COMPLETED;
        if (value instanceof RespValue.Error) {
            request.future.completeExceptionally(
                new BobaStrawConnectionException(((RespValue.Error) value).message)
            );
        } else {
            request.future.complete(value);
        }
    }

    private void checkIdle() {
        if (idlePingInterval.isZero() || idlePingInterval.isNegative() || closed || !readyForCommands) {
            return;
        }
        long now = System.nanoTime();
        if (healthCheckInFlight) {
            if (now - healthCheckDeadlineNanos >= 0L) {
                failAll(new BobaStrawConnectionException("Idle Redis health check timed out"));
            }
            return;
        }
        if (!outbound.isEmpty() || !pending.isEmpty()
            || now - lastActivityNanos < idlePingInterval.toNanos()) {
            return;
        }
        healthCheckInFlight = true;
        healthCheckDeadlineNanos = now + timeout.toNanos();
        executeConnected(new String[] { "PING" }).whenComplete((value, error) -> {
            healthCheckInFlight = false;
            lastActivityNanos = System.nanoTime();
            if (error != null) {
                close();
            }
        });
    }

    private void failAll(Throwable cause) {
        failAll(cause, true);
    }

    private void failAll(Throwable cause, boolean classifyCommandDelivery) {
        BobaStrawConnectionException error = cause instanceof BobaStrawConnectionException
            ? (BobaStrawConnectionException) cause
            : new BobaStrawConnectionException("Redis connection failed", cause);

        if (closed) {
            return;
        }
        terminalError = error;
        closeRequested = true;
        closed = true;

        List<Request> failed = new ArrayList<Request>(outbound.size() + pending.size() + preReady.size());
        failed.addAll(outbound);
        failed.addAll(pending);
        failed.addAll(preReady);
        outbound.clear();
        pending.clear();
        preReady.clear();

        ConnectionTask task;
        while ((task = submitted.poll()) != null) {
            task.fail(error);
        }

        ready.completeExceptionally(error);
        for (Request request : failed) {
            BobaStrawConnectionException requestError = classifyCommandDelivery
                ? deliveryFailure(request, error)
                : error;
            request.state = RequestState.COMPLETED;
            request.future.completeExceptionally(requestError);
        }
    }

    private BobaStrawConnectionException deliveryFailure(
        Request request, BobaStrawConnectionException connectionError
    ) {
        if (request.bytesWritten || request.state == RequestState.SENT
            || request.state == RequestState.CANCELLED_DRAINING) {
            return new BobaStrawCommandMayHaveExecutedException(
                "Redis connection failed after command bytes were written; the command may have executed",
                connectionError
            );
        }
        return new BobaStrawCommandNotSentException(
            "Redis connection failed before command bytes were written", connectionError
        );
    }

    private BobaStrawCommandNotSentException notSentFailure(
        BobaStrawConnectionException connectionError
    ) {
        return new BobaStrawCommandNotSentException(
            "Redis connection failed before command bytes were written", connectionError
        );
    }

    @Override
    public void close() {
        if (closed || closeRequested) {
            return;
        }
        closeRequested = true;
        submitted.add(new ConnectionTask() {
            @Override
            public void run() {
                failAll(new BobaStrawConnectionException("Client closed"), false);
            }

            @Override
            public void fail(BobaStrawConnectionException error) {
                // A failed connection is already terminal.
            }
        });
        Selector current = selector;
        if (current != null) {
            current.wakeup();
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

    private interface ConnectionTask {
        void run();

        void fail(BobaStrawConnectionException error);
    }

    private enum RequestState {
        QUEUED,
        WRITING,
        SENT,
        CANCELLED,
        CANCELLED_DRAINING,
        COMPLETED
    }

    private static final class Request {
        private final ByteBuffer buffer;
        private final CompletableFuture<RespValue> future = new CompletableFuture<RespValue>();
        private RequestState state = RequestState.QUEUED;
        private boolean bytesWritten;

        private Request(ByteBuffer buffer) {
            this.buffer = buffer;
        }
    }
}
