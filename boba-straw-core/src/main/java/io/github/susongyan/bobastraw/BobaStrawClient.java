package io.github.susongyan.bobastraw;

import io.github.susongyan.bobastraw.internal.NioConnection;
import io.github.susongyan.bobastraw.internal.TransactionConnectionPool;
import io.github.susongyan.bobastraw.protocol.RespValue;

import java.net.URI;
import java.time.Duration;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.Set;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

/**
 * Thread-safe Redis client. The first implementation targets standalone Redis;
 * the public builder deliberately leaves room for Sentinel and Cluster routing.
 */
public final class BobaStrawClient implements AutoCloseable {
    private static final ScheduledExecutorService TIMEOUTS =
        Executors.newScheduledThreadPool(1, runnable -> {
            Thread thread = new Thread(runnable, "boba-straw-timeouts");
            thread.setDaemon(true);
            return thread;
        });
    private volatile NioConnection connection;
    private final Duration commandTimeout;
    private final BobaStrawSyncCommands sync;
    private final BobaStrawAsyncCommands async;
    private final Set<NioConnection> dedicatedConnections =
        Collections.synchronizedSet(new HashSet<NioConnection>());
    private volatile boolean closed;
    private final ScheduledFuture<?> reconnectTask;
    private final AtomicLong connectionCreations = new AtomicLong();
    private final AtomicLong reconnects = new AtomicLong();
    private final int transactionPoolMaxSize;
    private TransactionConnectionPool transactionPool;

    private BobaStrawClient(Builder builder) {
        this.commandTimeout = builder.commandTimeout;
        this.connection = createConnection(builder.host, builder.port, builder.commandTimeout,
            builder.protocolVersion, builder.username, builder.password, builder.clientName,
            builder.idlePingInterval);
        reconnectTask = builder.reconnectInterval.isZero() ? null : TIMEOUTS.scheduleAtFixedRate(() -> {
            if (!closed && !connection.isOpen()) {
                synchronized (this) {
                    if (!closed && !connection.isOpen()) {
                        connection = createConnection(
                            connection.host(), connection.port(), commandTimeout,
                            connection.protocol(), connection.username(), connection.password(),
                            connection.clientName(), connection.idlePingInterval()
                        );
                        reconnects.incrementAndGet();
                    }
                }
            }
        }, builder.reconnectInterval.toMillis(), builder.reconnectInterval.toMillis(), TimeUnit.MILLISECONDS);
        this.transactionPoolMaxSize = builder.transactionPoolMaxSize;
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
                    builder.transactionAcquireTimeout, builder.transactionIdleTimeout
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
    }

    public CompletionStage<RespValue> executeAsync(String command, String... arguments) {
        return executeOn(sharedConnection(), command, arguments);
    }

    public CompletionStage<RespValue> executeBinaryAsync(byte[] command, byte[]... arguments) {
        byte[][] all = new byte[arguments.length + 1][];
        all[0] = command;
        System.arraycopy(arguments, 0, all, 1, arguments.length);
        CompletionStage<RespValue> operation = sharedConnection().execute(all);
        java.util.concurrent.CompletableFuture<RespValue> result =
            new java.util.concurrent.CompletableFuture<RespValue>();
        operation.whenComplete((value, error) -> {
            if (error == null) {
                result.complete(value);
            } else {
                result.completeExceptionally(error);
            }
        });
        result.whenComplete((value, error) -> {
            if (result.isCancelled()) {
                operation.toCompletableFuture().cancel(false);
            }
        });
        return result;
    }

    private synchronized NioConnection sharedConnection() {
        if (closed) {
            throw new BobaStrawConnectionException("Client is closed");
        }
        if (connection.isOpen()) {
            return connection;
        }
        connection = createConnection(
            connection.host(), connection.port(), commandTimeout,
            connection.protocol(), connection.username(), connection.password(),
            connection.clientName(), connection.idlePingInterval()
        );
        return connection;
    }

    private static NioConnection createConnection(
        String host,
        int port,
        Duration timeout,
        ProtocolVersion protocol,
        String username,
        String password,
        String clientName,
        Duration idlePingInterval
    ) {
        return new NioConnection(host, port, timeout, protocol, username, password, clientName,
            null, idlePingInterval);
    }

    CompletionStage<RespValue> executeOn(NioConnection target, String command, String... arguments) {
        String[] all = new String[arguments.length + 1];
        all[0] = command;
        System.arraycopy(arguments, 0, all, 1, arguments.length);
        CompletionStage<RespValue> operation = target.execute(all);
        java.util.concurrent.CompletableFuture<RespValue> result =
            new java.util.concurrent.CompletableFuture<RespValue>();
        operation.whenComplete((value, error) -> {
            if (error == null) {
                result.complete(value);
            } else {
                result.completeExceptionally(error);
            }
        });
        result.whenComplete((value, error) -> {
            if (result.isCancelled()) {
                operation.toCompletableFuture().cancel(false);
            }
        });
        TIMEOUTS.schedule(() -> {
            if (result.completeExceptionally(new BobaStrawCommandTimeoutException(
                "Command timed out; it may have been executed by Redis", null
            ))) {
                operation.toCompletableFuture().cancel(false);
            }
        }, commandTimeout.toNanos(), TimeUnit.NANOSECONDS);
        return result;
    }

    CompletionStage<List<RespValue>> executeBatch(List<String[]> commands) {
        CompletionStage<List<RespValue>> operation = sharedConnection().executeBatch(commands);
        java.util.concurrent.CompletableFuture<List<RespValue>> result =
            new java.util.concurrent.CompletableFuture<List<RespValue>>();
        operation.whenComplete((value, error) -> {
            if (error == null) {
                result.complete(value);
            } else {
                result.completeExceptionally(error);
            }
        });
        TIMEOUTS.schedule(() -> result.completeExceptionally(
            new BobaStrawCommandTimeoutException(
                "Pipeline timed out; commands may have been executed by Redis", null
            )
        ), commandTimeout.toNanos(), TimeUnit.NANOSECONDS);
        return result;
    }

    private NioConnection openDedicatedConnection() {
        NioConnection dedicated = new NioConnection(
            connection.host(), connection.port(), commandTimeout,
            connection.protocol(), connection.username(), connection.password(),
            connection.clientName()
        );
        dedicatedConnections.add(dedicated);
        return dedicated;
    }

    NioConnection openPubSubConnection(java.util.function.Consumer<RespValue> listener) {
        NioConnection dedicated = new NioConnection(
            connection.host(), connection.port(), commandTimeout,
            connection.protocol(), connection.username(), connection.password(),
            connection.clientName(), listener, connection.idlePingInterval()
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
            return result.toCompletableFuture().get(
                commandTimeout.toMillis(),
                java.util.concurrent.TimeUnit.MILLISECONDS
            );
        } catch (java.util.concurrent.TimeoutException e) {
            throw new BobaStrawCommandTimeoutException(
                "Command timed out; it may have been executed by Redis",
                e
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
        if (reconnectTask != null) {
            reconnectTask.cancel(false);
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

        public BobaStrawClient build() {
            return new BobaStrawClient(this);
        }
    }
}
