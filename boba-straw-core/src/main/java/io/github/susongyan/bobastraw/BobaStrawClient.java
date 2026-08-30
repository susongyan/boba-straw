package io.github.susongyan.bobastraw;

import io.github.susongyan.bobastraw.internal.NioConnection;
import io.github.susongyan.bobastraw.protocol.RespValue;

import java.net.URI;
import java.time.Duration;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

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
    private final NioConnection connection;
    private final Duration commandTimeout;
    private final BobaStrawSyncCommands sync;
    private final BobaStrawAsyncCommands async;

    private BobaStrawClient(Builder builder) {
        this.commandTimeout = builder.commandTimeout;
        this.connection = new NioConnection(
            builder.host,
            builder.port,
            builder.commandTimeout,
            builder.protocolVersion,
            builder.username,
            builder.password,
            builder.clientName
        );
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
        return new BobaStrawTransaction(this);
    }

    public CompletionStage<RespValue> executeAsync(String command, String... arguments) {
        String[] all = new String[arguments.length + 1];
        all[0] = command;
        System.arraycopy(arguments, 0, all, 1, arguments.length);
        CompletionStage<RespValue> operation = connection.execute(all);
        java.util.concurrent.CompletableFuture<RespValue> result =
            new java.util.concurrent.CompletableFuture<RespValue>();
        operation.whenComplete((value, error) -> {
            if (error == null) {
                result.complete(value);
            } else {
                result.completeExceptionally(error);
            }
        });
        TIMEOUTS.schedule(() -> result.completeExceptionally(
            new BobaStrawCommandTimeoutException(
                "Command timed out; it may have been executed by Redis",
                null
            )
        ), commandTimeout.toNanos(), TimeUnit.NANOSECONDS);
        return result;
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
        connection.close();
    }

    public static final class Builder {
        private String host = "localhost";
        private int port = 6379;
        private Duration commandTimeout = Duration.ofSeconds(2);
        private ProtocolVersion protocolVersion = ProtocolVersion.AUTO;
        private String username;
        private String password;
        private String clientName;

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

        public BobaStrawClient build() {
            return new BobaStrawClient(this);
        }
    }
}
