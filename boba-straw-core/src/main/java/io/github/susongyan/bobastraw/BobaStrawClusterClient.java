package io.github.susongyan.bobastraw;

import io.github.susongyan.bobastraw.internal.NioConnection;
import io.github.susongyan.bobastraw.protocol.RespValue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;

/**
 * Redis Cluster client using CLUSTER SLOTS discovery and hash-slot routing.
 * Redirections are bounded to one retry so failures remain observable.
 */
public final class BobaStrawClusterClient implements AutoCloseable {
    private final Duration timeout;
    private final ProtocolVersion protocol;
    private final String username;
    private final String password;
    private final String clientName;
    private final Map<Integer, Node> slots = new HashMap<Integer, Node>();
    private final Map<String, Node> nodes = new HashMap<String, Node>();
    private final Object lock = new Object();

    private BobaStrawClusterClient(Builder builder) {
        this.timeout = builder.timeout;
        this.protocol = builder.protocol;
        this.username = builder.username;
        this.password = builder.password;
        this.clientName = builder.clientName;
        Node seed = node(builder.host, builder.port);
        refresh(seed);
    }

    public static Builder builder() {
        return new Builder();
    }

    public CompletionStage<RespValue> executeAsync(String command, String... arguments) {
        String key = arguments.length == 0 ? "" : arguments[0];
        Node target;
        synchronized (lock) {
            target = slots.get(ClusterSlot.of(key));
        }
        if (target == null) {
            target = nodes.values().iterator().next();
        }
        return execute(target, command, arguments, 0);
    }

    private CompletionStage<RespValue> execute(Node target, String command, String[] arguments, int redirects) {
        return target.connection.execute(join(command, arguments)).handle((value, error) -> {
            if (error == null) {
                return java.util.concurrent.CompletableFuture.completedFuture(value);
            }
            String message = rootMessage(error);
            if (redirects >= 1 || (!message.startsWith("MOVED ") && !message.startsWith("ASK "))) {
                java.util.concurrent.CompletableFuture<RespValue> failed = new java.util.concurrent.CompletableFuture<RespValue>();
                failed.completeExceptionally(error);
                return failed;
            }
            String[] parts = message.split(" ");
            if (parts.length < 3) {
                java.util.concurrent.CompletableFuture<RespValue> failed = new java.util.concurrent.CompletableFuture<RespValue>();
                failed.completeExceptionally(error);
                return failed;
            }
            int slot = Integer.parseInt(parts[1]);
            String[] address = parts[2].split(":");
            Node redirected = node(address[0], Integer.parseInt(address[1]));
            synchronized (lock) {
                slots.put(slot, redirected);
            }
            if (message.startsWith("ASK ")) {
                return redirected.connection.execute(join("ASKING", new String[0]))
                    .thenCompose(ignored -> redirected.connection.execute(join(command, arguments)))
                    .toCompletableFuture();
            }
            return execute(redirected, command, arguments, redirects + 1).toCompletableFuture();
        }).thenCompose(future -> future);
    }

    private void refresh(Node seed) {
        RespValue value = seed.connection.execute(new String[] { "CLUSTER", "SLOTS" })
            .toCompletableFuture().join();
        if (!(value instanceof RespValue.Array)) {
            throw new BobaStrawConnectionException("CLUSTER SLOTS returned an unexpected response");
        }
        synchronized (lock) {
            for (RespValue rangeValue : ((RespValue.Array) value).values) {
                List<RespValue> range = array(rangeValue);
                int first = (int) range.get(0).asLong();
                int last = (int) range.get(1).asLong();
                List<RespValue> address = array(range.get(2));
                Node node = node(address.get(0).asString(), (int) address.get(1).asLong());
                for (int slot = first; slot <= last; slot++) {
                    slots.put(slot, node);
                }
            }
        }
    }

    private Node node(String host, int port) {
        String id = host + ":" + port;
        synchronized (lock) {
            Node existing = nodes.get(id);
            if (existing != null) {
                return existing;
            }
            Node created = new Node(new NioConnection(
                host, port, timeout, protocol, username, password, clientName
            ));
            nodes.put(id, created);
            return created;
        }
    }

    private static String[] join(String command, String[] arguments) {
        String[] result = new String[arguments.length + 1];
        result[0] = command;
        System.arraycopy(arguments, 0, result, 1, arguments.length);
        return result;
    }

    private static List<RespValue> array(RespValue value) {
        if (!(value instanceof RespValue.Array)) {
            throw new BobaStrawConnectionException("Expected a Cluster array response");
        }
        return ((RespValue.Array) value).values;
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? "" : current.getMessage();
    }

    @Override
    public void close() {
        synchronized (lock) {
            for (Node node : nodes.values()) {
                node.connection.close();
            }
            nodes.clear();
            slots.clear();
        }
    }

    private static final class Node {
        private final NioConnection connection;

        private Node(NioConnection connection) {
            this.connection = connection;
        }
    }

    public static final class Builder {
        private String host = "localhost";
        private int port = 6379;
        private Duration timeout = Duration.ofSeconds(2);
        private ProtocolVersion protocol = ProtocolVersion.AUTO;
        private String username;
        private String password;
        private String clientName;

        public Builder seed(String host, int port) {
            this.host = host;
            this.port = port;
            return this;
        }

        public Builder commandTimeout(Duration value) {
            this.timeout = value;
            return this;
        }

        public Builder protocol(ProtocolVersion value) {
            this.protocol = value == null ? ProtocolVersion.AUTO : value;
            return this;
        }

        public Builder credentials(String user, String secret) {
            this.username = user;
            this.password = secret;
            return this;
        }

        public Builder clientName(String value) {
            this.clientName = value;
            return this;
        }

        public BobaStrawClusterClient build() {
            return new BobaStrawClusterClient(this);
        }
    }
}
