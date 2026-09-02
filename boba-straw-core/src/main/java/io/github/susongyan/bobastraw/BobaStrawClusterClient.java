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
        bootstrap(builder.seeds);
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

    private void bootstrap(List<Seed> configuredSeeds) {
        List<Seed> seeds = new ArrayList<Seed>(configuredSeeds);
        Collections.shuffle(seeds);
        RuntimeException lastFailure = null;
        for (Seed seed : seeds) {
            Node candidate = node(seed.host, seed.port);
            try {
                refresh(candidate);
                return;
            } catch (RuntimeException error) {
                lastFailure = error;
                remove(candidate, seed.host, seed.port);
            }
        }
        if (lastFailure == null) {
            throw new BobaStrawConnectionException("At least one Redis Cluster seed is required");
        }
        throw new BobaStrawConnectionException(
            "Could not discover Redis Cluster slots from any configured seed", lastFailure
        );
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

    private void remove(Node node, String host, int port) {
        synchronized (lock) {
            String id = host + ":" + port;
            if (nodes.get(id) == node) {
                nodes.remove(id);
            }
        }
        node.connection.close();
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

    private static final class Seed {
        private final String host;
        private final int port;

        private Seed(String host, int port) {
            this.host = host;
            this.port = port;
        }
    }

    public static final class Builder {
        private final List<Seed> seeds = new ArrayList<Seed>();
        private boolean explicitSeeds;
        private Duration timeout = Duration.ofSeconds(2);
        private ProtocolVersion protocol = ProtocolVersion.AUTO;
        private String username;
        private String password;
        private String clientName;

        private Builder() {
            seeds.add(new Seed("localhost", 6379));
        }

        /**
         * Adds a Cluster seed. Configure more than one seed so discovery can
         * continue when an individual startup node is unavailable.
         */
        public Builder seed(String host, int port) {
            if (!explicitSeeds) {
                seeds.clear();
                explicitSeeds = true;
            }
            addSeed(host, port);
            return this;
        }

        /**
         * Replaces the seed list with {@code host:port} endpoints. Bracketed
         * IPv6 literals such as {@code [::1]:6379} are supported.
         */
        public Builder seeds(String... endpoints) {
            if (endpoints == null || endpoints.length == 0) {
                throw new IllegalArgumentException("At least one Cluster seed is required");
            }
            seeds.clear();
            explicitSeeds = true;
            for (String endpoint : endpoints) {
                Seed seed = parseEndpoint(endpoint);
                addSeed(seed.host, seed.port);
            }
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

        private void addSeed(String host, int port) {
            if (host == null || host.trim().isEmpty()) {
                throw new IllegalArgumentException("Cluster seed host must not be empty");
            }
            if (port < 1 || port > 65535) {
                throw new IllegalArgumentException("Cluster seed port must be between 1 and 65535");
            }
            seeds.add(new Seed(host, port));
        }

        private static Seed parseEndpoint(String endpoint) {
            if (endpoint == null) {
                throw new IllegalArgumentException("Cluster seed endpoint must not be null");
            }
            String value = endpoint.trim();
            if (value.startsWith("[")) {
                int closingBracket = value.indexOf(']');
                if (closingBracket < 2 || closingBracket + 1 >= value.length()
                    || value.charAt(closingBracket + 1) != ':') {
                    throw new IllegalArgumentException("Invalid bracketed Cluster seed: " + endpoint);
                }
                return new Seed(
                    value.substring(1, closingBracket),
                    parsePort(value.substring(closingBracket + 2), endpoint)
                );
            }
            int separator = value.lastIndexOf(':');
            if (separator < 1 || separator == value.length() - 1 || value.indexOf(':') != separator) {
                throw new IllegalArgumentException("Cluster seed must use host:port: " + endpoint);
            }
            return new Seed(value.substring(0, separator), parsePort(value.substring(separator + 1), endpoint));
        }

        private static int parsePort(String value, String endpoint) {
            try {
                int port = Integer.parseInt(value);
                if (port < 1 || port > 65535) {
                    throw new NumberFormatException("outside valid port range");
                }
                return port;
            } catch (NumberFormatException error) {
                throw new IllegalArgumentException("Invalid Cluster seed port: " + endpoint, error);
            }
        }
    }
}
