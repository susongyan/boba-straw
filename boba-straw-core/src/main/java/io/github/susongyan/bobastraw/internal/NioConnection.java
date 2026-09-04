package io.github.susongyan.bobastraw.internal;

import io.github.susongyan.bobastraw.BobaStrawBackpressureException;
import io.github.susongyan.bobastraw.BobaStrawCommandMayHaveExecutedException;
import io.github.susongyan.bobastraw.BobaStrawCommandNotSentException;
import io.github.susongyan.bobastraw.BobaStrawCommandTimeoutException;
import io.github.susongyan.bobastraw.BobaStrawConnectionException;
import io.github.susongyan.bobastraw.BobaStrawConnectionLimits;
import io.github.susongyan.bobastraw.ProtocolVersion;
import io.github.susongyan.bobastraw.protocol.RespCodec;
import io.github.susongyan.bobastraw.protocol.RespLimits;
import io.github.susongyan.bobastraw.protocol.RespValue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

/** One non-blocking TCP connection with FIFO response matching. */
public final class NioConnection implements AutoCloseable {
    private final NioEventLoop eventLoop;
    private final NioEventLoopGroup legacyOwnedEventLoops;
    private final NioIoLimits ioLimits;
    private final String host;
    private final int port;
    private final Duration timeout;
    private final ProtocolVersion requestedProtocol;
    private final String username;
    private final String password;
    private final String clientName;
    private final Duration idlePingInterval;
    private final ConnectionCapacity capacity;
    private final Queue<Request> outbound = new ArrayDeque<Request>();
    private final Queue<Request> pending = new ArrayDeque<Request>();
    private final Queue<Request> preReady = new ArrayDeque<Request>();
    private final RespCodec.Decoder decoder;
    private final Consumer<RespValue> pushListener;
    private final BobaCallbackDispatcher callbackDispatcher;
    private final BobaCallbackDispatcher.SerialDispatcher pushCallbacks;
    private final ByteBuffer readBuffer;
    private final ByteBuffer[] writeBuffers;
    private final Request[] writeRequests;
    private final int[] writePositions;

    private volatile boolean closed;
    private volatile boolean closeRequested;
    private volatile BobaStrawConnectionException terminalError;
    private long lastActivityNanos = System.nanoTime();
    private boolean healthCheckInFlight;
    private volatile boolean readyForCommands;
    private boolean bufferedResponsesPending;
    private int responseBudgetRemainingThisTurn;
    private boolean lastWriteFrameLimited;
    private int lastWriteFrameOriginalLimit;
    private SelectionKey key;
    private SocketChannel channel;
    private final List<Runnable> closeListeners = new ArrayList<Runnable>();
    private final List<Runnable> readyListeners = new ArrayList<Runnable>();
    private boolean resourcesClosing;
    private boolean resourcesClosed;
    private volatile boolean drainingPushCallbacks;

    /**
     * @deprecated Internal compatibility constructor. Use BobaStrawClient or a shared
     * BobaStrawClientResources-backed factory instead.
     */
    @Deprecated
    public NioConnection(
        String host,
        int port,
        Duration timeout,
        ProtocolVersion requestedProtocol,
        String username,
        String password,
        String clientName
    ) {
        this(new NioEventLoopGroup(1), host, port, timeout, requestedProtocol, username, password,
            clientName, null, Duration.ZERO);
    }

    /**
     * @deprecated Internal compatibility constructor. Use BobaStrawClient or a shared
     * BobaStrawClientResources-backed factory instead.
     */
    @Deprecated
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
        this(new NioEventLoopGroup(1), host, port, timeout, requestedProtocol, username, password,
            clientName, pushListener, Duration.ZERO);
    }

    /**
     * @deprecated Internal compatibility constructor. Use BobaStrawClient or a shared
     * BobaStrawClientResources-backed factory instead.
     */
    @Deprecated
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
        this(new NioEventLoopGroup(1), host, port, timeout, requestedProtocol, username, password,
            clientName, pushListener, idlePingInterval);
    }

    private NioConnection(
        NioEventLoopGroup legacyOwnedEventLoops,
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
        this(legacyOwnedEventLoops.next(), host, port, timeout, requestedProtocol, username, password,
            clientName, pushListener, idlePingInterval, legacyOwnedEventLoops, RespLimits.defaults(),
            BobaStrawConnectionLimits.defaults(), null);
    }

    NioConnection(
        NioEventLoop eventLoop,
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
        this(eventLoop, host, port, timeout, requestedProtocol, username, password, clientName,
            pushListener, idlePingInterval, null, RespLimits.defaults(),
            BobaStrawConnectionLimits.defaults(), null);
    }

    NioConnection(
        NioEventLoop eventLoop,
        String host,
        int port,
        Duration timeout,
        ProtocolVersion requestedProtocol,
        String username,
        String password,
        String clientName,
        Consumer<RespValue> pushListener,
        Duration idlePingInterval,
        RespLimits respLimits
    ) {
        this(eventLoop, host, port, timeout, requestedProtocol, username, password, clientName,
            pushListener, idlePingInterval, null, respLimits, BobaStrawConnectionLimits.defaults(), null);
    }

    NioConnection(
        NioEventLoop eventLoop,
        String host,
        int port,
        Duration timeout,
        ProtocolVersion requestedProtocol,
        String username,
        String password,
        String clientName,
        Consumer<RespValue> pushListener,
        Duration idlePingInterval,
        RespLimits respLimits,
        BobaCallbackDispatcher callbackDispatcher
    ) {
        this(eventLoop, host, port, timeout, requestedProtocol, username, password, clientName,
            pushListener, idlePingInterval, null, respLimits, BobaStrawConnectionLimits.defaults(),
            callbackDispatcher);
    }

    NioConnection(
        NioEventLoop eventLoop,
        String host,
        int port,
        Duration timeout,
        ProtocolVersion requestedProtocol,
        String username,
        String password,
        String clientName,
        Consumer<RespValue> pushListener,
        Duration idlePingInterval,
        RespLimits respLimits,
        BobaStrawConnectionLimits connectionLimits,
        BobaCallbackDispatcher callbackDispatcher
    ) {
        this(eventLoop, host, port, timeout, requestedProtocol, username, password, clientName,
            pushListener, idlePingInterval, null, respLimits, connectionLimits, callbackDispatcher);
    }

    private NioConnection(
        NioEventLoop eventLoop,
        String host,
        int port,
        Duration timeout,
        ProtocolVersion requestedProtocol,
        String username,
        String password,
        String clientName,
        Consumer<RespValue> pushListener,
        Duration idlePingInterval,
        NioEventLoopGroup legacyOwnedEventLoops,
        RespLimits respLimits,
        BobaStrawConnectionLimits connectionLimits,
        BobaCallbackDispatcher callbackDispatcher
    ) {
        if (respLimits == null) {
            throw new IllegalArgumentException("respLimits must not be null");
        }
        if (connectionLimits == null) {
            throw new IllegalArgumentException("connectionLimits must not be null");
        }
        this.eventLoop = eventLoop;
        this.legacyOwnedEventLoops = legacyOwnedEventLoops;
        this.ioLimits = eventLoop.ioLimits();
        this.host = host;
        this.port = port;
        this.timeout = timeout;
        this.requestedProtocol = requestedProtocol;
        this.username = username;
        this.password = password;
        this.clientName = clientName;
        this.pushListener = pushListener;
        this.callbackDispatcher = callbackDispatcher;
        this.pushCallbacks = callbackDispatcher == null ? null : callbackDispatcher.serialDispatcher();
        this.idlePingInterval = idlePingInterval == null ? Duration.ZERO : idlePingInterval;
        this.capacity = new ConnectionCapacity(connectionLimits);
        this.decoder = new RespCodec.Decoder(respLimits);
        this.readBuffer = ByteBuffer.allocate(ioLimits.readBufferSize);
        this.writeBuffers = new ByteBuffer[ioLimits.maxGatheringFrames];
        this.writeRequests = new Request[ioLimits.maxGatheringFrames];
        this.writePositions = new int[ioLimits.maxGatheringFrames];
        this.responseBudgetRemainingThisTurn = ioLimits.maxDecodedResponsesPerTurn;
        submit(new ConnectionTask() {
            @Override
            public void run() {
                NioConnection.this.eventLoop.register(NioConnection.this);
            }

            @Override
            public void fail(BobaStrawConnectionException error) {
                failBeforeRegistration(error);
            }
        });
    }

    public CompletionStage<RespValue> execute(String[] command) {
        return executeExternal(RespCodec.encodeCommand(command));
    }

    public CompletionStage<RespValue> execute(byte[][] command) {
        return executeExternal(RespCodec.encodeCommand(command));
    }

    /**
     * Executes an application command and completes on the EventLoop after Redis replies.
     *
     * <p>This internal transport entry point exists for Boba Straw's blocking facade. Public
     * asynchronous APIs must use {@link #execute(String[])} or {@link #execute(byte[][])} so
     * their application-visible completion is isolated from the EventLoop.</p>
     */
    public CompletionStage<RespValue> executeTransport(String[] command) {
        return executeTransportExternal(RespCodec.encodeCommand(command));
    }

    /**
     * Sends a Pub/Sub command whose action must run after listener callbacks decoded before its
     * response. This is used for unsubscribe cleanup so a successful acknowledgement cannot
     * discard an earlier message merely because callbacks execute off the EventLoop.
     */
    public CompletionStage<RespValue> executeAfterPushCallbacks(
        String[] command,
        Runnable afterResponse
    ) {
        if (afterResponse == null) {
            throw new IllegalArgumentException("afterResponse must not be null");
        }
        byte[] encoded = RespCodec.encodeCommand(command);
        ConnectionCapacity.Reservation capacityReservation = reserveCapacity(encoded.length);
        if (capacityReservation == null) {
            return connectionBackpressure("Redis command was not sent");
        }
        return exposeTransportToCaller(
            enqueueExternal(encoded, capacityReservation, afterResponse)
        );
    }

    /**
     * Sends a Pub/Sub command that drains listener callbacks accepted before its response.
     *
     * <p>This is used by unsubscribe cleanup. The physical transport may be released immediately
     * after its acknowledgement while the serial callback stream finishes callbacks already
     * accepted before that acknowledgement.</p>
     */
    public CompletionStage<RespValue> executeAfterPushCallbacks(String[] command) {
        return executeAfterPushCallbacks(command, new Runnable() {
            @Override
            public void run() {
                // The serial barrier itself closes the callback stream after earlier callbacks drain.
            }
        });
    }

    private CompletionStage<RespValue> executeExternal(byte[] encoded) {
        ConnectionCapacity.Reservation capacityReservation = reserveCapacity(encoded.length);
        if (capacityReservation == null) {
            return connectionBackpressure("Redis command was not sent");
        }
        BobaCallbackDispatcher.Reservation reservation = reserveCallbackSlot();
        if (callbackDispatcher != null && reservation == null) {
            capacityReservation.releaseAll();
            return failedStage(new BobaStrawBackpressureException(
                "Boba Straw callback capacity is exhausted; Redis command was not sent"
            ));
        }
        return exposeToCaller(enqueueExternal(encoded, capacityReservation), reservation);
    }

    private CompletionStage<RespValue> executeTransportExternal(byte[] encoded) {
        ConnectionCapacity.Reservation capacityReservation = reserveCapacity(encoded.length);
        if (capacityReservation == null) {
            return connectionBackpressure("Redis command was not sent");
        }
        return exposeTransportToCaller(enqueueExternal(encoded, capacityReservation));
    }

    private CompletableFuture<RespValue> exposeToCaller(
        final Request request,
        final BobaCallbackDispatcher.Reservation reservation
    ) {
        final CompletableFuture<RespValue> result = new CompletableFuture<RespValue>();
        request.future.whenComplete((value, requestError) -> {
            if (result.isDone()) {
                releaseUndispatched(reservation);
                return;
            }
            if (reservation == null) {
                completeResult(result, value, requestError);
                return;
            }
            if (!reservation.dispatch(new Runnable() {
                @Override
                public void run() {
                    completeResult(result, value, requestError);
                }
            }) && !result.isDone()) {
                result.completeExceptionally(new BobaStrawBackpressureException(
                    "Boba Straw callback dispatcher closed before the Redis result could be delivered"
                ));
            }
        });
        result.whenComplete((value, error) -> {
            if (result.isCancelled()) {
                releaseUndispatched(reservation);
                cancel(request);
            }
        });
        return result;
    }

    /**
     * Exposes an internal transport completion without routing it through application callbacks.
     *
     * <p>The blocking facade needs this path so it cannot be delayed by a saturated callback
     * worker. It still owns the same cancellation hook as a public asynchronous command: a
     * caller that cancels this stage asks the EventLoop to remove the request or retain its FIFO
     * response-drain slot as appropriate.</p>
     */
    private CompletableFuture<RespValue> exposeTransportToCaller(final Request request) {
        final CompletableFuture<RespValue> result = new CompletableFuture<RespValue>();
        request.future.whenComplete((value, requestError) -> {
            completeResult(result, value, requestError);
        });
        result.whenComplete((value, error) -> {
            if (result.isCancelled()) {
                cancel(request);
            }
        });
        return result;
    }

    public CompletionStage<List<RespValue>> executeBatch(List<String[]> commands) {
        List<byte[]> frames = new ArrayList<byte[]>(commands.size());
        for (String[] command : commands) {
            frames.add(RespCodec.encodeCommand(command));
        }
        if (frames.isEmpty()) {
            return CompletableFuture.completedFuture(new ArrayList<RespValue>());
        }

        List<ConnectionCapacity.Reservation> capacityReservations = reserveCapacity(frames);
        if (capacityReservations == null) {
            return connectionBackpressure("Redis pipeline was not sent");
        }

        List<BobaCallbackDispatcher.Reservation> reservations =
            new ArrayList<BobaCallbackDispatcher.Reservation>(frames.size());
        for (int index = 0; index < frames.size(); index++) {
            BobaCallbackDispatcher.Reservation reservation = reserveCallbackSlot();
            if (callbackDispatcher != null && reservation == null) {
                releaseUndispatched(reservations);
                releaseCapacity(capacityReservations);
                return failedStage(new BobaStrawBackpressureException(
                    "Boba Straw callback capacity is exhausted; Redis pipeline was not sent"
                ));
            }
            reservations.add(reservation);
        }

        List<CompletableFuture<RespValue>> futures = new ArrayList<CompletableFuture<RespValue>>();
        final List<Request> requests = new ArrayList<Request>(frames.size());
        for (int index = 0; index < frames.size(); index++) {
            Request request = newRequest(frames.get(index), capacityReservations.get(index));
            requests.add(request);
            futures.add(exposeToCaller(request, reservations.get(index)));
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

    private BobaCallbackDispatcher.Reservation reserveCallbackSlot() {
        return callbackDispatcher == null ? null : callbackDispatcher.tryReserve();
    }

    private ConnectionCapacity.Reservation reserveCapacity(int encodedBytes) {
        return capacity.tryReserve(encodedBytes);
    }

    private List<ConnectionCapacity.Reservation> reserveCapacity(List<byte[]> frames) {
        long[] lengths = new long[frames.size()];
        for (int index = 0; index < frames.size(); index++) {
            lengths[index] = frames.get(index).length;
        }
        return capacity.tryReserveBatch(lengths);
    }

    private static <T> CompletionStage<T> connectionBackpressure(String detail) {
        return failedStage(new BobaStrawBackpressureException(
            "Boba Straw connection capacity is exhausted; " + detail
        ));
    }

    private static void completeResult(
        CompletableFuture<RespValue> result,
        RespValue value,
        Throwable error
    ) {
        if (error == null) {
            result.complete(value);
        } else {
            result.completeExceptionally(error);
        }
    }

    private static void releaseUndispatched(BobaCallbackDispatcher.Reservation reservation) {
        if (reservation != null) {
            reservation.releaseIfUndispatched();
        }
    }

    private static void releaseUndispatched(
        List<BobaCallbackDispatcher.Reservation> reservations
    ) {
        for (BobaCallbackDispatcher.Reservation reservation : reservations) {
            releaseUndispatched(reservation);
        }
    }

    private static void releaseCapacity(List<ConnectionCapacity.Reservation> reservations) {
        for (ConnectionCapacity.Reservation reservation : reservations) {
            reservation.releaseAll();
        }
    }

    private static void releaseRequestCapacity(Request request) {
        if (request.capacityReservation != null) {
            request.capacityReservation.releaseAll();
        }
    }

    private static void releaseWrittenCapacity(Request request, long writtenBytes) {
        if (request.capacityReservation != null) {
            request.capacityReservation.releaseWrittenBytes(writtenBytes);
        }
    }

    private static <T> CompletionStage<T> failedStage(Throwable error) {
        CompletableFuture<T> result = new CompletableFuture<T>();
        result.completeExceptionally(error);
        return result;
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
        return !closed && !closeRequested && eventLoop.isOpen();
    }

    /** Returns whether the protocol handshake completed and application commands may be written. */
    public boolean isReadyForCommands() {
        return readyForCommands && isOpen();
    }

    /** Returns accepted application commands whose response slots have not been drained. */
    public int inFlightCommands() {
        return capacity.commands();
    }

    /** Returns encoded application-command bytes that have not yet been written to the socket. */
    public long queuedWriteBytes() {
        return capacity.bytes();
    }

    /** Returns locally rejected application commands on this physical connection. */
    public long connectionBackpressureRejections() {
        return capacity.rejections();
    }

    public Duration idlePingInterval() {
        return idlePingInterval;
    }

    /** Schedules internal connection work on this connection's EventLoop. */
    public void schedule(Runnable action, Duration delay) {
        if (action == null) {
            throw new IllegalArgumentException("scheduled action must not be null");
        }
        if (delay == null || delay.isNegative()) {
            throw new IllegalArgumentException("scheduled delay must not be negative");
        }
        eventLoop.schedule(action, delay.toNanos());
    }

    /**
     * Registers an internal lifecycle listener that runs after this connection releases its
     * socket and selector state. More than one internal observer may register.
     */
    public void onClose(Runnable listener) {
        if (listener == null) {
            throw new IllegalArgumentException("close listener must not be null");
        }
        boolean notifyNow;
        synchronized (this) {
            notifyNow = resourcesClosed;
            if (!notifyNow) {
                closeListeners.add(listener);
            }
        }
        if (notifyNow) {
            notifyLifecycleListener(listener);
        }
    }

    /** Registers an internal lifecycle listener that runs after protocol negotiation succeeds. */
    public void onReady(Runnable listener) {
        if (listener == null) {
            throw new IllegalArgumentException("ready listener must not be null");
        }
        boolean notifyNow;
        synchronized (this) {
            notifyNow = readyForCommands;
            if (!notifyNow && !resourcesClosed) {
                readyListeners.add(listener);
            }
        }
        if (notifyNow) {
            notifyLifecycleListener(listener);
        }
    }

    void register(Selector selector) throws IOException {
        if (closed) {
            return;
        }
        channel = SocketChannel.open();
        channel.configureBlocking(false);
        boolean connected = channel.connect(new InetSocketAddress(host, port));
        key = channel.register(selector, connected ? SelectionKey.OP_READ : SelectionKey.OP_CONNECT, this);
        if (connected) {
            startHandshake();
        }
    }

    void onSelected(SelectionKey selectedKey) throws IOException {
        if (closed || !selectedKey.isValid()) {
            return;
        }
        if (selectedKey.isConnectable()) {
            connect(selectedKey);
        }
        if (selectedKey.isWritable()) {
            write(selectedKey);
        }
        if (selectedKey.isReadable()) {
            read();
        }
    }

    void onTick() throws IOException {
        if (closed) {
            return;
        }
        try {
            if (bufferedResponsesPending && responseBudgetRemainingThisTurn > 0) {
                drainAvailableResponses();
            }
            armWrites();
            checkIdle();
        } finally {
            responseBudgetRemainingThisTurn = ioLimits.maxDecodedResponsesPerTurn;
        }
    }

    boolean hasImmediateWork() {
        return bufferedResponsesPending;
    }

    void onIoFailure(Throwable error) {
        failAll(error);
        closeResources();
    }

    void onRegistrationRejected(BobaStrawConnectionException error) {
        failAll(error, false);
        closeResources();
    }

    void onEventLoopShutdown(BobaStrawConnectionException error) {
        failAll(error, false);
        closeResources();
    }

    private CompletionStage<RespValue> executeConnected(String[] command) {
        return enqueueConnected(command).future;
    }

    private Request newRequest(byte[] encoded) {
        return newRequest(encoded, null);
    }

    private Request newRequest(
        byte[] encoded,
        ConnectionCapacity.Reservation capacityReservation
    ) {
        final Request request = new Request(ByteBuffer.wrap(encoded), capacityReservation);
        request.future.whenComplete((value, error) -> {
            NioEventLoop.ScheduledTask deadline = request.deadline;
            if (deadline != null) {
                deadline.cancel();
            }
        });
        return request;
    }

    private Request enqueueExternal(
        byte[] encoded,
        ConnectionCapacity.Reservation capacityReservation
    ) {
        return enqueueExternal(encoded, capacityReservation, null);
    }

    private Request enqueueExternal(
        byte[] encoded,
        ConnectionCapacity.Reservation capacityReservation,
        Runnable pushCompletionBarrier
    ) {
        final Request request = newRequest(encoded, capacityReservation);
        request.pushCompletionBarrier = pushCompletionBarrier;
        submit(new ConnectionTask() {
            @Override
            public void run() {
                if (request.cancellationRequested) {
                    cancelQueuedRequest(request);
                    return;
                }
                if (readyForCommands) {
                    outbound.add(request);
                } else {
                    preReady.add(request);
                }
                scheduleRequestDeadline(request);
            }

            @Override
            public void fail(BobaStrawConnectionException error) {
                releaseRequestCapacity(request);
                request.future.completeExceptionally(notSentFailure(error));
            }
        });
        return request;
    }

    private void enqueueExternal(final List<Request> requests) {
        submit(new ConnectionTask() {
            @Override
            public void run() {
                for (Request request : requests) {
                    if (request.cancellationRequested) {
                        cancelQueuedRequest(request);
                    }
                }
                if (readyForCommands) {
                    for (Request request : requests) {
                        if (!request.cancellationRequested) {
                            outbound.add(request);
                            scheduleRequestDeadline(request);
                        }
                    }
                } else {
                    for (Request request : requests) {
                        if (!request.cancellationRequested) {
                            preReady.add(request);
                            scheduleRequestDeadline(request);
                        }
                    }
                }
            }

            @Override
            public void fail(BobaStrawConnectionException error) {
                for (Request request : requests) {
                    releaseRequestCapacity(request);
                    request.future.completeExceptionally(notSentFailure(error));
                }
            }
        });
    }

    private Request enqueueConnected(String[] command) {
        return enqueueConnected(RespCodec.encodeCommand(command));
    }

    private Request enqueueConnected(byte[] encoded) {
        final Request request = newRequest(encoded);
        submit(new ConnectionTask() {
            @Override
            public void run() {
                if (request.cancellationRequested) {
                    cancelQueuedRequest(request);
                    return;
                }
                outbound.add(request);
                scheduleRequestDeadline(request);
            }

            @Override
            public void fail(BobaStrawConnectionException error) {
                request.future.completeExceptionally(error);
            }
        });
        return request;
    }

    private void cancel(final Request request) {
        request.cancellationRequested = true;
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

    private void submit(final ConnectionTask task) {
        BobaStrawConnectionException error = terminalError;
        if (error != null) {
            task.fail(error);
            return;
        }
        if (closeRequested) {
            task.fail(new BobaStrawConnectionException("Client is closed"));
            return;
        }
        eventLoop.execute(new NioEventLoop.Task() {
            @Override
            public void run() {
                if (closed) {
                    BobaStrawConnectionException error = terminalError;
                    task.fail(error == null ? new BobaStrawConnectionException("Client is closed") : error);
                    return;
                }
                task.run();
            }

            @Override
            public void reject(BobaStrawConnectionException error) {
                task.fail(error);
            }
        });
    }

    private void failBeforeRegistration(BobaStrawConnectionException error) {
        if (closed) {
            return;
        }
        terminalError = error;
        closeRequested = true;
        closed = true;
        if (pushCallbacks != null) {
            pushCallbacks.close();
        }
        if (legacyOwnedEventLoops != null) {
            legacyOwnedEventLoops.close();
        }
        markResourcesClosed();
    }

    private void cancelOnEventLoop(Request request) {
        if (request.state == RequestState.QUEUED) {
            if (outbound.remove(request) || preReady.remove(request)) {
                cancelQueuedRequest(request);
            }
            return;
        }
        if (request.state == RequestState.WRITING || request.state == RequestState.SENT) {
            request.state = RequestState.CANCELLED_DRAINING;
            request.future.completeExceptionally(new java.util.concurrent.CancellationException());
        }
    }

    private void cancelQueuedRequest(Request request) {
        request.state = RequestState.CANCELLED;
        releaseRequestCapacity(request);
        request.future.completeExceptionally(new java.util.concurrent.CancellationException());
    }

    private void scheduleRequestDeadline(final Request request) {
        long timeoutNanos = timeout.toNanos();
        long elapsedNanos = System.nanoTime() - request.createdAtNanos;
        long delayNanos = elapsedNanos >= timeoutNanos ? 0L : timeoutNanos - elapsedNanos;
        request.deadline = eventLoop.schedule(new Runnable() {
            @Override
            public void run() {
                timeoutRequestOnEventLoop(request);
            }
        }, delayNanos);
        if (request.future.isDone()) {
            request.deadline.cancel();
        }
    }

    private void timeoutRequestOnEventLoop(Request request) {
        if (request.future.isDone()) {
            return;
        }
        if (request.state == RequestState.QUEUED) {
            if (outbound.remove(request) || preReady.remove(request)) {
                request.state = RequestState.CANCELLED;
                releaseRequestCapacity(request);
                request.future.completeExceptionally(new BobaStrawCommandTimeoutException(
                    "Redis command timed out before any command bytes were written", null, false
                ));
            }
            return;
        }
        if (request.state == RequestState.WRITING || request.state == RequestState.SENT) {
            request.state = RequestState.CANCELLED_DRAINING;
            request.future.completeExceptionally(new BobaStrawCommandTimeoutException(
                "Redis command timed out after command bytes may have been written; "
                    + "the command may have executed",
                null,
                true
            ));
        }
    }

    private void connect(SelectionKey selectedKey) throws IOException {
        if (channel.finishConnect()) {
            selectedKey.interestOps(SelectionKey.OP_READ);
            startHandshake();
        }
    }

    private void startHandshake() {
        if (requestedProtocol == ProtocolVersion.RESP2) {
            authenticateResp2();
            return;
        }

        executeConnected(helloCommand()).whenComplete((response, error) -> {
            if (error == null) {
                activateUserCommands();
                return;
            }
            if (requestedProtocol == ProtocolVersion.AUTO && isUnknownHello(error)) {
                authenticateResp2();
                return;
            }
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
                close();
            }
        });
    }

    private void activateUserCommands() {
        readyForCommands = true;
        while (!preReady.isEmpty()) {
            outbound.add(preReady.remove());
        }
        notifyReadyListeners();
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
        if (channel == null || !channel.isConnected() || key == null || !key.isValid()) {
            return;
        }
        if (!outbound.isEmpty()) {
            key.interestOps(key.interestOps() | SelectionKey.OP_WRITE);
        }
    }

    private void write(SelectionKey selectedKey) throws IOException {
        if (outbound.isEmpty()) {
            selectedKey.interestOps(selectedKey.interestOps() & ~SelectionKey.OP_WRITE);
            return;
        }

        int frameCount = prepareWriteBatch();
        if (frameCount == 0) {
            return;
        }

        ByteBuffer limitedBuffer = lastWriteFrameLimited ? writeBuffers[frameCount - 1] : null;
        long written = 0L;
        boolean writeCompleted = false;
        try {
            written = channel.write(writeBuffers, 0, frameCount);
            writeCompleted = true;
        } catch (IOException error) {
            markWriteBatchAsPossiblySent(frameCount);
            throw error;
        } finally {
            if (lastWriteFrameLimited) {
                limitedBuffer.limit(lastWriteFrameOriginalLimit);
            }
            if (!writeCompleted) {
                clearWriteBatch(frameCount);
            }
        }
        if (written > 0L) {
            lastActivityNanos = System.nanoTime();
        }

        try {
            finishWrittenRequests(frameCount);
        } finally {
            clearWriteBatch(frameCount);
        }
        if (outbound.isEmpty()) {
            selectedKey.interestOps(selectedKey.interestOps() & ~SelectionKey.OP_WRITE);
        }
    }

    private void markWriteBatchAsPossiblySent(int frameCount) {
        for (int index = 0; index < frameCount; index++) {
            writeRequests[index].writeMayHaveReachedServer = true;
        }
    }

    private void read() throws IOException {
        int remainingReadBytes = ioLimits.maxReadBytesPerTurn;
        while (remainingReadBytes > 0) {
            readBuffer.clear();
            int readLimit = Math.min(readBuffer.capacity(), remainingReadBytes);
            readBuffer.limit(readLimit);
            int count = channel.read(readBuffer);
            if (count == -1) {
                throw new IOException("Redis closed the connection");
            }
            if (count == 0) {
                return;
            }
            lastActivityNanos = System.nanoTime();
            remainingReadBytes -= count;
            processInbound(readBuffer.array(), count);
            if (responseBudgetRemainingThisTurn == 0) {
                return;
            }
        }
    }

    /**
     * Accepts bytes already read by an EventLoop-owned transport.
     *
     * <p>This is package-private so internal transports can share the same RESP dispatch path;
     * application code cannot inject responses.</p>
     */
    void processInbound(byte[] source, int length) throws IOException {
        if (!eventLoop.isEventLoopThread()) {
            throw new IllegalStateException("Inbound Redis bytes must be processed on the EventLoop");
        }
        if (source == null || length < 0 || length > source.length) {
            throw new IllegalArgumentException("Inbound Redis byte length is invalid");
        }
        decoder.feed(source, length);
        drainAvailableResponses();
    }

    private void drainAvailableResponses() throws IOException {
        if (responseBudgetRemainingThisTurn == 0) {
            bufferedResponsesPending = true;
            return;
        }
        int dispatched = drainDecodedResponses(responseBudgetRemainingThisTurn);
        responseBudgetRemainingThisTurn -= dispatched;
    }

    private int prepareWriteBatch() {
        int frameCount = 0;
        int remainingBytes = ioLimits.maxWriteBytesPerTurn;
        lastWriteFrameLimited = false;
        lastWriteFrameOriginalLimit = 0;
        for (Request request : outbound) {
            if (frameCount == ioLimits.maxGatheringFrames || remainingBytes == 0) {
                break;
            }
            ByteBuffer buffer = request.buffer;
            int bytes = buffer.remaining();
            if (bytes == 0) {
                throw new IllegalStateException("Outbound Redis request has no remaining command bytes");
            }
            writeBuffers[frameCount] = buffer;
            writeRequests[frameCount] = request;
            writePositions[frameCount] = buffer.position();
            frameCount++;
            if (bytes >= remainingBytes) {
                if (bytes > remainingBytes) {
                    lastWriteFrameLimited = true;
                    lastWriteFrameOriginalLimit = buffer.limit();
                    buffer.limit(buffer.position() + remainingBytes);
                }
                break;
            }
            remainingBytes -= bytes;
        }
        return frameCount;
    }

    private void finishWrittenRequests(int frameCount) {
        for (int index = 0; index < frameCount; index++) {
            Request request = writeRequests[index];
            ByteBuffer buffer = request.buffer;
            if (buffer.position() > writePositions[index]) {
                releaseWrittenCapacity(request, buffer.position() - writePositions[index]);
                request.bytesWritten = true;
                if (request.state == RequestState.QUEUED) {
                    request.state = RequestState.WRITING;
                }
            }
            if (buffer.hasRemaining()) {
                return;
            }
            if (outbound.peek() != request) {
                throw new IllegalStateException("Outbound Redis request order changed while writing");
            }
            outbound.remove();
            request.buffer = null;
            pending.add(request);
            if (request.state != RequestState.CANCELLED_DRAINING) {
                request.state = RequestState.SENT;
            }
        }
    }

    private void clearWriteBatch(int frameCount) {
        for (int index = 0; index < frameCount; index++) {
            writeBuffers[index] = null;
            writeRequests[index] = null;
            writePositions[index] = 0;
        }
        lastWriteFrameLimited = false;
        lastWriteFrameOriginalLimit = 0;
    }

    private int drainDecodedResponses(int budget) throws IOException {
        int processed = 0;
        RespValue value;
        while (processed < budget && (value = decoder.poll()) != null) {
            dispatch(value);
            processed++;
        }
        bufferedResponsesPending = processed == budget;
        return processed;
    }

    private void dispatch(RespValue value) throws IOException {
        RespValue payload = value instanceof RespValue.Attribute
            ? ((RespValue.Attribute) value).value
            : value;
        if (payload instanceof RespValue.Push) {
            if (pushListener != null) {
                if (isPubSubAcknowledgement(payload)) {
                    completeNextRequest(payload);
                } else {
                    dispatchPush(payload);
                }
            }
            return;
        }
        if (pushListener != null && isPubSubMessage(payload)) {
            dispatchPush(payload);
            return;
        }
        completeNextRequest(value);
    }

    private void dispatchPush(final RespValue payload) throws IOException {
        if (pushCallbacks == null) {
            pushListener.accept(payload);
            return;
        }
        if (!pushCallbacks.execute(new Runnable() {
            @Override
            public void run() {
                pushListener.accept(payload);
            }
        })) {
            throw new IOException(
                "Boba Straw Pub/Sub callback capacity is exhausted; closing the subscription connection"
            );
        }
    }

    private void runPushCompletionBarrier(Request request) {
        if (request.pushCompletionBarrier == null) {
            return;
        }
        final Runnable action = request.pushCompletionBarrier;
        request.pushCompletionBarrier = null;
        if (pushCallbacks == null) {
            notifyLifecycleListener(action);
            return;
        }
        if (!pushCallbacks.executeBarrier(new Runnable() {
            @Override
            public void run() {
                try {
                    action.run();
                } finally {
                    pushCallbacks.close();
                    drainingPushCallbacks = false;
                }
            }
        })) {
            pushCallbacks.close();
            drainingPushCallbacks = false;
            notifyLifecycleListener(action);
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
        List<RespValue> values;
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
            releaseRequestCapacity(request);
            runPushCompletionBarrier(request);
            return;
        }
        request.state = RequestState.COMPLETED;
        releaseRequestCapacity(request);
        if (value instanceof RespValue.Error) {
            request.future.completeExceptionally(
                new BobaStrawConnectionException(((RespValue.Error) value).message)
            );
        } else {
            request.future.complete(value);
        }
        runPushCompletionBarrier(request);
    }

    private void checkIdle() {
        if (idlePingInterval.isZero() || idlePingInterval.isNegative() || !readyForCommands) {
            return;
        }
        long now = System.nanoTime();
        if (healthCheckInFlight) {
            return;
        }
        if (!outbound.isEmpty() || !pending.isEmpty()
            || now - lastActivityNanos < idlePingInterval.toNanos()) {
            return;
        }
        healthCheckInFlight = true;
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

        for (Request request : failed) {
            BobaStrawConnectionException requestError = classifyCommandDelivery
                ? deliveryFailure(request, error)
                : error;
            request.state = RequestState.COMPLETED;
            releaseRequestCapacity(request);
            request.future.completeExceptionally(requestError);
        }
    }

    private BobaStrawConnectionException deliveryFailure(
        Request request, BobaStrawConnectionException connectionError
    ) {
        if (request.bytesWritten || request.writeMayHaveReachedServer || request.state == RequestState.SENT
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
            if (drainingPushCallbacks && pushCallbacks != null) {
                pushCallbacks.close();
            }
            return;
        }
        closeRequested = true;
        eventLoop.execute(new NioEventLoop.Task() {
            @Override
            public void run() {
                failAll(new BobaStrawConnectionException("Client closed"), false);
                closeResources();
            }

            @Override
            public void reject(BobaStrawConnectionException error) {
                // EventLoop shutdown owns failure completion for registered connections.
            }
        });
    }

    /**
     * Releases socket and selector ownership after a successful unsubscribe acknowledgement.
     *
     * <p>Callbacks accepted before the acknowledgement remain in the connection's serial stream
     * and drain behind its barrier. A normal subsequent {@link #close()} still aborts that drain,
     * which is appropriate for application or Resources shutdown.</p>
     */
    public void closeTransportAfterPushCallbacks() {
        if (closed || closeRequested) {
            return;
        }
        closeRequested = true;
        eventLoop.execute(new NioEventLoop.Task() {
            @Override
            public void run() {
                failAll(new BobaStrawConnectionException("Client closed"), false);
                closeResources(false);
            }

            @Override
            public void reject(BobaStrawConnectionException error) {
                // EventLoop shutdown owns failure completion for registered connections.
            }
        });
    }

    private void closeResources() {
        closeResources(true);
    }

    private void closeResources(boolean closePushCallbacks) {
        synchronized (this) {
            if (resourcesClosing || resourcesClosed) {
                return;
            }
            resourcesClosing = true;
            drainingPushCallbacks = !closePushCallbacks;
        }
        try {
            if (closePushCallbacks && pushCallbacks != null) {
                pushCallbacks.close();
            }
            SelectionKey currentKey = key;
            key = null;
            if (currentKey != null) {
                currentKey.cancel();
            }
            SocketChannel currentChannel = channel;
            channel = null;
            if (currentChannel != null) {
                try {
                    currentChannel.close();
                } catch (IOException ignored) {
                    // Closing is best-effort.
                }
            }
            eventLoop.detach(this);
            if (legacyOwnedEventLoops != null) {
                legacyOwnedEventLoops.close();
            }
        } finally {
            markResourcesClosed();
        }
    }

    private void markResourcesClosed() {
        synchronized (this) {
            resourcesClosing = false;
            resourcesClosed = true;
            readyListeners.clear();
        }
        notifyRegisteredCloseListeners();
    }

    private void notifyRegisteredCloseListeners() {
        List<Runnable> listeners;
        synchronized (this) {
            if (closeListeners.isEmpty()) {
                return;
            }
            listeners = new ArrayList<Runnable>(closeListeners);
            closeListeners.clear();
        }
        for (Runnable listener : listeners) {
            notifyLifecycleListener(listener);
        }
    }

    private void notifyReadyListeners() {
        List<Runnable> listeners;
        synchronized (this) {
            if (readyListeners.isEmpty()) {
                return;
            }
            listeners = new ArrayList<Runnable>(readyListeners);
            readyListeners.clear();
        }
        for (Runnable listener : listeners) {
            notifyLifecycleListener(listener);
        }
    }

    private void notifyLifecycleListener(Runnable listener) {
        try {
            listener.run();
        } catch (Throwable ignored) {
            // Internal lifecycle observers cannot interfere with socket cleanup.
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
        private ByteBuffer buffer;
        private final CompletableFuture<RespValue> future = new CompletableFuture<RespValue>();
        private final long createdAtNanos = System.nanoTime();
        private final ConnectionCapacity.Reservation capacityReservation;
        private RequestState state = RequestState.QUEUED;
        private volatile boolean cancellationRequested;
        private volatile NioEventLoop.ScheduledTask deadline;
        private boolean bytesWritten;
        private boolean writeMayHaveReachedServer;
        private Runnable pushCompletionBarrier;

        private Request(ByteBuffer buffer, ConnectionCapacity.Reservation capacityReservation) {
            this.buffer = buffer;
            this.capacityReservation = capacityReservation;
        }
    }
}
