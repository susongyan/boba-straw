package io.github.susongyan.bobastraw;

import io.github.susongyan.bobastraw.internal.NioConnection;
import io.github.susongyan.bobastraw.internal.NioConnectionFactory;
import io.github.susongyan.bobastraw.internal.TransactionConnectionPool;
import io.github.susongyan.bobastraw.protocol.RespLimits;
import io.github.susongyan.bobastraw.protocol.RespValue;

import java.net.URI;
import java.time.Duration;
import java.util.Collections;
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
    private final BobaStrawSyncCommands sync;
    private final BobaStrawAsyncCommands async;
    private final Duration reconnectInterval;
    private final Set<NioConnection> dedicatedConnections =
        Collections.synchronizedSet(new HashSet<NioConnection>());
    private volatile boolean closed;
    private long reconnectGeneration;
    private final AtomicLong connectionCreations = new AtomicLong();
    private final AtomicLong reconnects = new AtomicLong();
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
        this.reconnectInterval = builder.reconnectInterval;
        this.connection = createConnection(builder.host, builder.port, builder.commandTimeout,
            builder.protocolVersion, builder.username, builder.password, builder.clientName,
            builder.idlePingInterval);
        scheduleReconnectCheck();
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
                    connection.host(), connection.port(), commandTimeout,
                    connection.protocol(), connection.username(), connection.password(),
                    connection.clientName(), transactionPoolMaxSize,
                    transactionAcquireTimeout, transactionIdleTimeout, connectionFactory, respLimits
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
        if (connection.isOpen()) {
            return connection;
        }
        connection = createConnection(
            connection.host(), connection.port(), commandTimeout,
            connection.protocol(), connection.username(), connection.password(),
            connection.clientName(), connection.idlePingInterval()
        );
        scheduleReconnectCheck();
        return connection;
    }

    private NioConnection createConnection(
        String host,
        int port,
        Duration timeout,
        ProtocolVersion protocol,
        String username,
        String password,
        String clientName,
        Duration idlePingInterval
    ) {
        NioConnection created = connectionFactory.create(
            host, port, timeout, protocol, username, password, clientName,
            null, idlePingInterval, respLimits);
        connectionCreations.incrementAndGet();
        return created;
    }

    CompletionStage<RespValue> executeOn(NioConnection target, String command, String... arguments) {
        String[] all = new String[arguments.length + 1];
        all[0] = command;
        System.arraycopy(arguments, 0, all, 1, arguments.length);
        return target.execute(all);
    }

    CompletionStage<List<RespValue>> executeBatch(List<String[]> commands) {
        return sharedConnection().executeBatch(commands);
    }

    private synchronized void scheduleReconnectCheck() {
        if (closed || reconnectInterval.isZero() || !resources.isOpen()) {
            return;
        }
        final long generation = ++reconnectGeneration;
        final NioConnection scheduledConnection = connection;
        scheduledConnection.schedule(new Runnable() {
            @Override
            public void run() {
                synchronized (BobaStrawClient.this) {
                    if (closed || !resources.isOpen() || generation != reconnectGeneration) {
                        return;
                    }
                    if (!connection.isOpen()) {
                        connection = createConnection(
                            connection.host(), connection.port(), commandTimeout,
                            connection.protocol(), connection.username(), connection.password(),
                            connection.clientName(), connection.idlePingInterval()
                        );
                        reconnects.incrementAndGet();
                    }
                    scheduleReconnectCheck();
                }
            }
        }, reconnectInterval);
    }

    NioConnection openPubSubConnection(java.util.function.Consumer<RespValue> listener) {
        ensureClientOpen();
        NioConnection dedicated = connectionFactory.create(
            connection.host(), connection.port(), commandTimeout,
            connection.protocol(), connection.username(), connection.password(),
            connection.clientName(), listener, Duration.ZERO, respLimits
        );
        dedicatedConnections.add(dedicated);
        return dedicated;
    }

    void closeDedicated(NioConnection dedicated) {
        dedicatedConnections.remove(dedicated);
        dedicated.close();
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
        }
        connection.close();
        synchronized (this) {
            if (transactionPool != null) {
                transactionPool.close();
                transactionPool = null;
            }
        }
        synchronized (dedicatedConnections) {
            for (NioConnection dedicated : dedicatedConnections) {
                dedicated.close();
            }
            dedicatedConnections.clear();
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
        private BobaStrawClientResources resources;
        private RespLimits respLimits = RespLimits.defaults();

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
         * Uses externally owned selector resources. Closing this client will not close them.
         */
        public Builder resources(BobaStrawClientResources value) {
            this.resources = value;
            return this;
        }

        public BobaStrawClient build() {
            return new BobaStrawClient(this);
        }
    }
}
