package io.github.susongyan.bobastraw;

import io.github.susongyan.bobastraw.internal.NioConnection;
import io.github.susongyan.bobastraw.internal.NioConnectionFactory;
import io.github.susongyan.bobastraw.internal.TransactionConnectionPool;
import io.github.susongyan.bobastraw.protocol.RespLimits;
import io.github.susongyan.bobastraw.protocol.RespValue;

import java.net.URI;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe Redis client. The first implementation targets standalone Redis;
 * the public builder deliberately leaves room for Sentinel and Cluster routing.
 */
public final class BobaStrawClient implements AutoCloseable {
    private volatile NioConnection connection;
    private final BobaStrawClientResources resources;
    private final boolean ownsResources;
    private final NioConnectionFactory connectionFactory;
    private final Duration commandTimeout;
    private final RespLimits respLimits;
    private final BobaStrawConnectionLimits connectionLimits;
    private final BobaStrawSyncCommands sync;
    private final BobaStrawAsyncCommands async;
    private final Duration reconnectInterval;
    private final Duration reconnectMaxInterval;
    private final String host;
    private final int port;
    private final ProtocolVersion protocolVersion;
    private final String username;
    private final String password;
    private final String clientName;
    private final Duration idlePingInterval;
    private final Object dedicatedConnectionLock = new Object();
    private final Set<NioConnection> dedicatedConnections = new HashSet<NioConnection>();
    private final Set<NioConnection> drainingPubSubConnections = new HashSet<NioConnection>();
    private volatile boolean closed;
    private volatile BobaStrawConnectionState sharedConnectionState = BobaStrawConnectionState.CONNECTING;
    private long reconnectGeneration;
    private boolean reconnectScheduled;
    private NioConnectionFactory.ScheduledTask reconnectTask;
    private boolean sharedConnectionReady;
    private boolean sharedConnectionIsReconnect;
    private int consecutiveReconnectFailures;
    private Duration reconnectDelay;
    private volatile Duration nextReconnectDelay = Duration.ZERO;
    private final AtomicLong connectionCreations = new AtomicLong();
    private final AtomicLong reconnects = new AtomicLong();
    private final AtomicLong successfulReconnects = new AtomicLong();
    private final int transactionPoolMaxSize;
    private final Duration transactionAcquireTimeout;
    private final Duration transactionIdleTimeout;
    private TransactionConnectionPool transactionPool;

    private BobaStrawClient(Builder builder) {
        this.ownsResources = builder.resources == null;
        this.resources = ownsResources ? BobaStrawClientResources.builder().build() : builder.resources;
        if (!resources.isOpen()) {
            throw new BobaStrawConnectionException("Boba Straw client resources are closed");
        }
        this.connectionFactory = resources.connectionFactory();
        this.commandTimeout = builder.commandTimeout;
        this.respLimits = builder.respLimits;
        this.connectionLimits = builder.connectionLimits;
        this.reconnectInterval = builder.reconnectInterval;
        this.reconnectMaxInterval = builder.reconnectMaxInterval;
        this.host = builder.host;
        this.port = builder.port;
        this.protocolVersion = builder.protocolVersion;
        this.username = builder.username;
        this.password = builder.password;
        this.clientName = builder.clientName;
        this.idlePingInterval = builder.idlePingInterval;
        this.reconnectDelay = reconnectInterval;
        installSharedConnection(createConnection(), false);
        this.transactionPoolMaxSize = builder.transactionPoolMaxSize;
        this.transactionAcquireTimeout = builder.transactionAcquireTimeout;
        this.transactionIdleTimeout = builder.transactionIdleTimeout;
        this.sync = new BobaStrawSyncCommands(this);
        this.async = new BobaStrawAsyncCommands(this);
    }

    public static Builder builder() {
        return new Builder();
    }

    public BobaStrawSyncCommands sync() {
        return sync;
    }

    public BobaStrawAsyncCommands async() {
        return async;
    }

    public BobaStrawBinaryCommands binary() {
        return new BobaStrawBinaryCommands(this);
    }

    public BobaStrawPipeline pipeline() {
        return new BobaStrawPipeline(this);
    }

    public BobaStrawTransaction transaction() {
        ensureClientOpen();
        synchronized (this) {
            if (transactionPool == null) {
                transactionPool = new TransactionConnectionPool(
                    host, port, commandTimeout, protocolVersion, username, password,
                    clientName, transactionPoolMaxSize,
                    transactionAcquireTimeout, transactionIdleTimeout, connectionFactory, respLimits,
                    connectionLimits
                );
            }
            return new BobaStrawTransaction(this, transactionPool.acquire());
        }
    }

    public BobaStrawPubSub pubSub() {
        ensureClientOpen();
        return new BobaStrawPubSub(this);
    }

    public long connectionCreations() {
        return connectionCreations.get();
    }

    public long reconnects() {
        return reconnects.get();
    }

    /** Number of replacement connections that completed connect, negotiation, and authentication. */
    public long successfulReconnects() {
        return successfulReconnects.get();
    }

    /** Returns a non-blocking snapshot of the shared connection's lifecycle and admission state. */
    public synchronized BobaStrawClientMetrics metrics() {
        NioConnection current = connection;
        BobaStrawConnectionState state = closed || !resources.isOpen()
            ? BobaStrawConnectionState.CLOSED
            : sharedConnectionState;
        return new BobaStrawClientMetrics(
            state,
            connectionCreations.get(),
            reconnects.get(),
            successfulReconnects.get(),
            consecutiveReconnectFailures,
            state == BobaStrawConnectionState.BACKING_OFF ? nextReconnectDelay : Duration.ZERO,
            current == null ? 0 : current.inFlightCommands(),
            current == null ? 0L : current.queuedWriteBytes(),
            current == null ? 0L : current.connectionBackpressureRejections()
        );
    }

    private void ensureClientOpen() {
        if (closed) {
            throw new BobaStrawConnectionException("Client is closed");
        }
        if (!resources.isOpen()) {
            throw new BobaStrawConnectionException("Boba Straw client resources are closed");
        }
    }

    public CompletionStage<RespValue> executeAsync(String command, String... arguments) {
        return executeOn(sharedConnection(), command, arguments);
    }

    public CompletionStage<RespValue> executeBinaryAsync(byte[] command, byte[]... arguments) {
        byte[][] all = new byte[arguments.length + 1][];
        all[0] = command;
        System.arraycopy(arguments, 0, all, 1, arguments.length);
        return sharedConnection().execute(all);
    }

    private synchronized NioConnection sharedConnection() {
        ensureClientOpen();
        return connection;
    }

    private NioConnection createConnection() {
        NioConnection created = connectionFactory.create(
            host, port, commandTimeout, protocolVersion, username, password, clientName,
            null, idlePingInterval, respLimits, connectionLimits);
        connectionCreations.incrementAndGet();
        return created;
    }

    CompletionStage<RespValue> executeOn(NioConnection target, String command, String... arguments) {
        String[] all = new String[arguments.length + 1];
        all[0] = command;
        System.arraycopy(arguments, 0, all, 1, arguments.length);
        return target.execute(all);
    }

    CompletionStage<RespValue> executeTransport(String command, String... arguments) {
        String[] all = new String[arguments.length + 1];
        all[0] = command;
        System.arraycopy(arguments, 0, all, 1, arguments.length);
        return sharedConnection().executeTransport(all);
    }

    CompletionStage<List<RespValue>> executeBatch(List<String[]> commands) {
        return sharedConnection().executeBatch(commands);
    }

    private synchronized void installSharedConnection(
        final NioConnection replacement,
        boolean reconnect
    ) {
        connection = replacement;
        sharedConnectionReady = false;
        sharedConnectionIsReconnect = reconnect;
        sharedConnectionState = BobaStrawConnectionState.CONNECTING;
        nextReconnectDelay = Duration.ZERO;
        replacement.onReady(new Runnable() {
            @Override
            public void run() {
                onSharedConnectionReady(replacement);
            }
        });
        replacement.onClose(new Runnable() {
            @Override
            public void run() {
                onSharedConnectionClosed(replacement);
            }
        });
    }

    private synchronized void onSharedConnectionReady(NioConnection candidate) {
        if (closed || candidate != connection) {
            return;
        }
        sharedConnectionReady = true;
        sharedConnectionState = BobaStrawConnectionState.READY;
        if (sharedConnectionIsReconnect) {
            successfulReconnects.incrementAndGet();
        }
        sharedConnectionIsReconnect = false;
        consecutiveReconnectFailures = 0;
        reconnectDelay = reconnectInterval;
        nextReconnectDelay = Duration.ZERO;
    }

    private synchronized void onSharedConnectionClosed(NioConnection candidate) {
        if (candidate != connection) {
            return;
        }
        if (closed || !resources.isOpen()) {
            sharedConnectionState = BobaStrawConnectionState.CLOSED;
            nextReconnectDelay = Duration.ZERO;
            return;
        }

        Duration delay = reconnectDelay;
        if (sharedConnectionReady) {
            consecutiveReconnectFailures = 0;
            delay = reconnectInterval;
        } else {
            consecutiveReconnectFailures++;
        }
        sharedConnectionReady = false;
        sharedConnectionState = BobaStrawConnectionState.BACKING_OFF;
        nextReconnectDelay = delay;
        reconnectDelay = doubledAtMost(delay, reconnectMaxInterval);
        scheduleReconnectAttempt(candidate, delay);
    }

    private void scheduleReconnectAttempt(final NioConnection failed, Duration delay) {
        if (reconnectScheduled || closed || !resources.isOpen()) {
            return;
        }
        reconnectScheduled = true;
        final long generation = ++reconnectGeneration;
        reconnectTask = connectionFactory.schedule(new Runnable() {
            @Override
            public void run() {
                synchronized (BobaStrawClient.this) {
                    if (generation != reconnectGeneration) {
                        return;
                    }
                    reconnectScheduled = false;
                    reconnectTask = null;
                    if (closed || !resources.isOpen() || connection != failed || connection.isOpen()) {
                        return;
                    }
                    reconnects.incrementAndGet();
                    installSharedConnection(createConnection(), true);
                }
            }
        }, delay);
    }

    private static Duration doubledAtMost(Duration value, Duration maximum) {
        long valueNanos = value.toNanos();
        long maximumNanos = maximum.toNanos();
        if (valueNanos >= maximumNanos || valueNanos > Long.MAX_VALUE / 2L) {
            return maximum;
        }
        long doubled = valueNanos * 2L;
        return Duration.ofNanos(Math.min(doubled, maximumNanos));
    }

    NioConnection openPubSubConnection(java.util.function.Consumer<RespValue> listener) {
        ensureClientOpen();
        final NioConnection dedicated = connectionFactory.create(
            host, port, commandTimeout, protocolVersion, username, password,
            clientName, listener, Duration.ZERO, respLimits, connectionLimits
        );
        boolean closeImmediately;
        synchronized (dedicatedConnectionLock) {
            closeImmediately = closed;
            if (!closeImmediately) {
                dedicatedConnections.add(dedicated);
            }
        }
        if (closeImmediately) {
            dedicated.close();
            throw new BobaStrawConnectionException("Client is closed");
        }
        dedicated.onClose(new Runnable() {
            @Override
            public void run() {
                synchronized (dedicatedConnectionLock) {
                    dedicatedConnections.remove(dedicated);
                }
            }
        });
        return dedicated;
    }

    void closeDedicated(NioConnection dedicated) {
        synchronized (dedicatedConnectionLock) {
            dedicatedConnections.remove(dedicated);
            drainingPubSubConnections.remove(dedicated);
        }
        dedicated.close();
    }

    /** Releases an acknowledged subscription socket while its already accepted listeners drain. */
    void closeDedicatedAfterAcknowledgement(NioConnection dedicated) {
        boolean abortDrain;
        synchronized (dedicatedConnectionLock) {
            dedicatedConnections.remove(dedicated);
            abortDrain = closed;
            if (!abortDrain) {
                drainingPubSubConnections.add(dedicated);
            }
        }
        if (abortDrain) {
            dedicated.close();
        } else {
            dedicated.closeTransportAfterPushCallbacks();
        }
    }

    /** Forgets a transport-free subscription after its serial callback barrier has drained. */
    void onDedicatedPushCallbacksDrained(NioConnection dedicated) {
        synchronized (dedicatedConnectionLock) {
            drainingPubSubConnections.remove(dedicated);
        }
    }

    void releaseTransaction(NioConnection transaction, boolean healthy) {
        synchronized (this) {
            if (transactionPool == null) {
                transaction.close();
            } else if (healthy) {
                transactionPool.release(transaction);
            } else {
                transactionPool.destroy(transaction);
            }
        }
    }

    <T> T await(CompletionStage<T> result) {
        try {
            return result.toCompletableFuture().get();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new BobaStrawConnectionException(
                "Interrupted while waiting for a Redis command",
                error
            );
        } catch (Exception e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            throw new BobaStrawConnectionException("Redis command failed", cause);
        }
    }

    @Override
    public void close() {
        closed = true;
        synchronized (this) {
            reconnectGeneration++;
            reconnectScheduled = false;
            if (reconnectTask != null) {
                reconnectTask.cancel();
                reconnectTask = null;
            }
            sharedConnectionState = BobaStrawConnectionState.CLOSED;
            nextReconnectDelay = Duration.ZERO;
        }
        connection.close();
        synchronized (this) {
            if (transactionPool != null) {
                transactionPool.close();
                transactionPool = null;
            }
        }
        Set<NioConnection> dedicatedToClose = new HashSet<NioConnection>();
        synchronized (dedicatedConnectionLock) {
            dedicatedToClose.addAll(dedicatedConnections);
            dedicatedToClose.addAll(drainingPubSubConnections);
            dedicatedConnections.clear();
            drainingPubSubConnections.clear();
        }
        for (NioConnection dedicated : dedicatedToClose) {
            dedicated.close();
        }
        if (ownsResources) {
            resources.close();
        }
    }

    public static final class Builder {
        private String host = "localhost";
        private int port = 6379;
        private Duration commandTimeout = Duration.ofSeconds(2);
        private ProtocolVersion protocolVersion = ProtocolVersion.AUTO;
        private String username;
        private String password;
        private String clientName;
        private int transactionPoolMaxSize = 8;
        private Duration transactionAcquireTimeout = Duration.ofSeconds(1);
        private Duration transactionIdleTimeout = Duration.ofMinutes(1);
        private Duration idlePingInterval = Duration.ZERO;
        private Duration reconnectInterval = Duration.ofSeconds(1);
        private Duration reconnectMaxInterval = Duration.ofSeconds(30);
        private BobaStrawClientResources resources;
        private RespLimits respLimits = RespLimits.defaults();
        private BobaStrawConnectionLimits connectionLimits = BobaStrawConnectionLimits.defaults();

        public Builder uri(String value) {
            URI uri = URI.create(value);
            if (uri.getHost() == null) {
                throw new IllegalArgumentException("URI must include a host");
            }
            this.host = uri.getHost();
            this.port = uri.getPort() == -1 ? 6379 : uri.getPort();
            if (uri.getUserInfo() != null) {
                int separator = uri.getUserInfo().indexOf(':');
                if (separator >= 0) {
                    this.username = uri.getUserInfo().substring(0, separator);
                    this.password = uri.getUserInfo().substring(separator + 1);
                } else {
                    this.password = uri.getUserInfo();
                }
            }
            return this;
        }

        public Builder endpoint(String host, int port) {
            this.host = host;
            this.port = port;
            return this;
        }

        public Builder commandTimeout(Duration value) {
            if (value == null || value.isNegative() || value.isZero()) {
                throw new IllegalArgumentException("commandTimeout must be positive");
            }
            this.commandTimeout = value;
            return this;
        }

        public Builder protocol(ProtocolVersion value) {
            this.protocolVersion = value == null ? ProtocolVersion.AUTO : value;
            return this;
        }

        public Builder credentials(String username, String password) {
            this.username = username;
            this.password = password;
            return this;
        }

        public Builder password(String password) {
            this.username = null;
            this.password = password;
            return this;
        }

        public Builder clientName(String value) {
            this.clientName = value;
            return this;
        }

        public Builder transactionPoolMaxSize(int value) {
            if (value < 1) {
                throw new IllegalArgumentException("transactionPoolMaxSize must be positive");
            }
            this.transactionPoolMaxSize = value;
            return this;
        }

        public Builder transactionAcquireTimeout(Duration value) {
            if (value == null || value.isNegative() || value.isZero()) {
                throw new IllegalArgumentException("transactionAcquireTimeout must be positive");
            }
            this.transactionAcquireTimeout = value;
            return this;
        }

        public Builder transactionIdleTimeout(Duration value) {
            if (value == null || value.isNegative() || value.isZero()) {
                throw new IllegalArgumentException("transactionIdleTimeout must be positive");
            }
            this.transactionIdleTimeout = value;
            return this;
        }

        public Builder idlePingInterval(Duration value) {
            if (value == null || value.isNegative()) {
                throw new IllegalArgumentException("idlePingInterval must not be negative");
            }
            this.idlePingInterval = value;
            return this;
        }

        public Builder reconnectInterval(Duration value) {
            if (value == null || value.isNegative() || value.isZero()) {
                throw new IllegalArgumentException("reconnectInterval must be positive");
            }
            this.reconnectInterval = value;
            return this;
        }

        /** Sets the upper bound for exponential background reconnect delay. */
        public Builder reconnectMaxInterval(Duration value) {
            if (value == null || value.isNegative() || value.isZero()) {
                throw new IllegalArgumentException("reconnectMaxInterval must be positive");
            }
            this.reconnectMaxInterval = value;
            return this;
        }

        /**
         * Sets inbound RESP resource limits for shared, transaction, and Pub/Sub connections.
         */
        public Builder respLimits(RespLimits value) {
            if (value == null) {
                throw new IllegalArgumentException("respLimits must not be null");
            }
            this.respLimits = value;
            return this;
        }

        /**
         * Sets per-physical-connection command admission limits for shared, transaction, and
         * Pub/Sub connections. This is independent from shared Resources callback capacity.
         */
        public Builder connectionLimits(BobaStrawConnectionLimits value) {
            if (value == null) {
                throw new IllegalArgumentException("connectionLimits must not be null");
            }
            this.connectionLimits = value;
            return this;
        }

        /**
         * Uses externally owned selector resources. Closing this client will not close them.
         */
        public Builder resources(BobaStrawClientResources value) {
            this.resources = value;
            return this;
        }

        public BobaStrawClient build() {
            if (reconnectMaxInterval.compareTo(reconnectInterval) < 0) {
                throw new IllegalArgumentException(
                    "reconnectMaxInterval must not be less than reconnectInterval"
                );
            }
            return new BobaStrawClient(this);
        }
    }
}
