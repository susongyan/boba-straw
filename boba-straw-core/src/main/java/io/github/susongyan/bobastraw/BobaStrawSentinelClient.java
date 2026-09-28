package io.github.susongyan.bobastraw;

import io.github.susongyan.bobastraw.internal.NioConnection;
import io.github.susongyan.bobastraw.internal.NioConnectionFactory;
import io.github.susongyan.bobastraw.protocol.RespLimits;
import io.github.susongyan.bobastraw.protocol.RespValue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Sentinel-discovered primary, with no application command replay across failover. */
public final class BobaStrawSentinelClient implements AutoCloseable {
    private final Object lock = new Object();
    private final BobaStrawClientResources resources;
    private final boolean ownsResources;
    private final NioConnectionFactory factory;
    private final List<Endpoint> sentinels;
    private final String masterName;
    private final String sentinelUsername;
    private final String sentinelPassword;
    private final String username;
    private final String password;
    private final ProtocolVersion protocol;
    private final Duration commandTimeout;
    private final Duration discoveryTimeout;
    private final Duration refreshInterval;
    private final Duration reconnectInterval;
    private final Duration reconnectMaxInterval;
    private final RespLimits respLimits;
    private final BobaStrawConnectionLimits connectionLimits;
    private NioConnection master;
    private Endpoint masterEndpoint;
    private Discovery discovery;
    private NioConnectionFactory.ScheduledTask scheduled;
    private long generation;
    private long successes;
    private long failures;
    private Duration retryDelay;
    private boolean closed;

    private BobaStrawSentinelClient(Builder builder) {
        ownsResources = builder.resources == null;
        resources = ownsResources ? BobaStrawClientResources.builder().build() : builder.resources;
        factory = resources.connectionFactory();
        sentinels = new ArrayList<Endpoint>(builder.sentinels);
        Collections.shuffle(sentinels);
        masterName = builder.masterName;
        sentinelUsername = builder.sentinelUsername;
        sentinelPassword = builder.sentinelPassword;
        username = builder.username;
        password = builder.password;
        protocol = builder.protocol;
        commandTimeout = builder.commandTimeout;
        discoveryTimeout = builder.discoveryTimeout;
        refreshInterval = builder.refreshInterval;
        reconnectInterval = builder.reconnectInterval;
        reconnectMaxInterval = builder.reconnectMaxInterval;
        retryDelay = reconnectInterval;
        respLimits = builder.respLimits;
        connectionLimits = builder.connectionLimits;
        try {
            // Internal completion: construction never waits for application callback workers.
            discover().toCompletableFuture().join();
        } catch (RuntimeException error) {
            close();
            throw new BobaStrawConnectionException("Sentinel bootstrap failed for " + masterName, unwrap(error));
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Typed ordinary String commands against the discovered primary; no implicit command replay. */
    public BobaStrawAsyncCommands async() {
        return new BobaStrawAsyncCommands(this::executeAsync);
    }

    public BobaStrawScanCommands scan() {
        return new BobaStrawScanCommands(this::executeAsync, true);
    }

    /** Ordinary String commands only. Dedicated/stateful operations need a separate topology API. */
    public CompletionStage<RespValue> executeAsync(String command, String... arguments) {
        validateOrdinary(command, arguments);
        NioConnection target;
        synchronized (lock) {
            ensureOpen();
            target = master;
            if (target == null || !target.isOpen()) {
                CompletableFuture<RespValue> failed = new CompletableFuture<RespValue>();
                failed.completeExceptionally(new BobaStrawCommandNotSentException(
                    "Sentinel primary is being discovered; command was not sent", null));
                return failed;
            }
        }
        String[] all = new String[arguments.length + 1];
        all[0] = command;
        System.arraycopy(arguments, 0, all, 1, arguments.length);
        CompletionStage<RespValue> result = target.execute(all);
        result.whenComplete((value, error) -> {
            Throwable failure = unwrap(error);
            if (failure instanceof BobaStrawCommandTimeoutException
                || failure instanceof BobaStrawCommandMayHaveExecutedException
                || (failure instanceof BobaStrawServerException
                    && failure.getMessage().startsWith("READONLY "))) {
                invalidate(target);
            }
        });
        // Return the underlying request, so cancellation preserves its FIFO placeholder.
        return result;
    }

    /** Cancelling this caller's view does not cancel discovery shared by other callers. */
    public CompletionStage<Void> refreshTopology() {
        return resources.exposeCompletion(discover());
    }

    public String masterAddress() {
        synchronized (lock) {
            return masterEndpoint == null ? null : masterEndpoint.toString();
        }
    }

    public BobaStrawConnectionState connectionState() {
        synchronized (lock) {
            if (closed || !resources.isOpen()) {
                return BobaStrawConnectionState.CLOSED;
            }
            return master != null && master.isOpen() ? BobaStrawConnectionState.READY
                : discovery != null ? BobaStrawConnectionState.CONNECTING : BobaStrawConnectionState.BACKING_OFF;
        }
    }

    public long successfulDiscoveries() {
        synchronized (lock) {
            return successes;
        }
    }

    public long failedDiscoveries() {
        synchronized (lock) {
            return failures;
        }
    }

    private CompletionStage<Void> discover() {
        Discovery next;
        synchronized (lock) {
            ensureOpen();
            if (discovery != null) {
                return discovery.result;
            }
            if (scheduled != null) {
                scheduled.cancel();
                scheduled = null;
            }
            generation++;
            next = new Discovery(new ArrayList<Endpoint>(sentinels));
            discovery = next;
        }
        next.attempt(0, null);
        return next.result;
    }

    private void schedule(Duration delay) {
        if (closed || !resources.isOpen()) {
            return;
        }
        if (scheduled != null) {
            scheduled.cancel();
        }
        long token = ++generation;
        scheduled = factory.schedule(() -> {
            synchronized (lock) {
                if (closed || !resources.isOpen() || token != generation) {
                    return;
                }
                scheduled = null;
            }
            try {
                discover();
            } catch (BobaStrawConnectionException ignored) {
                // Concurrent client/resources closure owns cleanup.
            }
        }, delay);
    }

    private void invalidate(NioConnection connection) {
        synchronized (lock) {
            if (master == connection) {
                master = null;
                masterEndpoint = null;
                if (discovery == null) {
                    schedule(retryDelay);
                }
            }
        }
        connection.closeForTopologyChange();
    }

    private NioConnection connect(Endpoint endpoint, boolean sentinel) {
        return factory.create(endpoint.host, endpoint.port, sentinel ? discoveryTimeout : commandTimeout,
            protocol, sentinel ? sentinelUsername : username, sentinel ? sentinelPassword : password,
            null, null, Duration.ZERO, respLimits, connectionLimits);
    }

    private final class Discovery {
        private final List<Endpoint> candidates;
        private final CompletableFuture<Void> result = new CompletableFuture<Void>();
        private NioConnection query;
        private NioConnection candidate;
        private int unknownNames;

        private Discovery(List<Endpoint> candidates) {
            this.candidates = candidates;
        }

        private void attempt(int index, Throwable last) {
            final NioConnection sentinel;
            synchronized (lock) {
                if (!active()) {
                    return;
                }
                if (index == candidates.size()) {
                    sentinel = null;
                } else {
                    sentinel = connect(candidates.get(index), true);
                    query = sentinel;
                }
            }
            if (sentinel == null) {
                finish(new BobaStrawConnectionException(unknownNames == candidates.size()
                    ? "Sentinels do not know master name " + masterName
                    : "No Sentinel supplied a reachable, verified primary for " + masterName, last));
                return;
            }
            sentinel.executeTransport(new String[] {"SENTINEL", "get-master-addr-by-name", masterName})
                .whenComplete((value, error) -> {
                    sentinel.close();
                    synchronized (lock) {
                        if (!active()) {
                            return;
                        }
                        query = null;
                    }
                    if (error != null) {
                        attempt(index + 1, unwrap(error));
                        return;
                    }
                    try {
                        if (value instanceof RespValue.Null) {
                            unknownNames++;
                            attempt(index + 1, new BobaStrawConnectionException("Unknown Sentinel master name"));
                            return;
                        }
                        List<RespValue> address = array(value);
                        if (address.size() != 2) {
                            throw new BobaStrawProtocolException("Invalid Sentinel master endpoint");
                        }
                        verify(new Endpoint(address.get(0).asString(),
                            Integer.parseInt(address.get(1).asString())), index);
                    } catch (RuntimeException invalid) {
                        attempt(index + 1, invalid);
                    }
                });
        }

        private void verify(Endpoint endpoint, int index) {
            final NioConnection checking;
            synchronized (lock) {
                if (!active()) {
                    return;
                }
                checking = master != null && master.isOpen() && endpoint.same(masterEndpoint)
                    ? master : connect(endpoint, false);
                candidate = checking;
            }
            checking.executeTransport(new String[] {"ROLE"}).whenComplete((value, error) -> {
                Throwable failure = unwrap(error);
                if (failure == null) {
                    try {
                        List<RespValue> role = array(value);
                        if (role.isEmpty() || !"master".equals(role.get(0).asString())) {
                            throw new BobaStrawConnectionException("Sentinel returned a non-primary Redis node");
                        }
                    } catch (RuntimeException invalid) {
                        failure = invalid;
                    }
                }
                if (failure != null) {
                    boolean retain;
                    synchronized (lock) {
                        if (!active()) {
                            return;
                        }
                        candidate = null;
                        retain = checking == master && failure instanceof BobaStrawBackpressureException;
                    }
                    if (!retain) {
                        invalidate(checking);
                    }
                    attempt(index + 1, failure);
                    return;
                }
                NioConnection previous;
                boolean accepted;
                synchronized (lock) {
                    if (!active()) {
                        return;
                    }
                    accepted = checking.isOpen();
                    if (!accepted) {
                        candidate = null;
                        previous = null;
                    } else {
                        previous = master;
                        master = checking;
                        masterEndpoint = endpoint;
                        candidate = null;
                        Endpoint reachable = candidates.get(index);
                        sentinels.remove(reachable);
                        sentinels.add(0, reachable);
                    }
                }
                if (!accepted) {
                    attempt(index + 1, new BobaStrawConnectionException("Verified primary disconnected"));
                    return;
                }
                if (previous != checking) {
                    checking.onClose(() -> invalidate(checking));
                }
                if (previous != null && previous != checking) {
                    previous.closeForTopologyChange();
                }
                finish(null);
            });
        }

        private boolean active() {
            return !closed && discovery == this && !result.isDone();
        }

        private void finish(Throwable failure) {
            synchronized (lock) {
                if (!active()) {
                    return;
                }
                discovery = null;
                if (failure == null) {
                    successes++;
                    retryDelay = reconnectInterval;
                    schedule(master != null && master.isOpen() ? refreshInterval : retryDelay);
                } else {
                    failures++;
                    schedule(retryDelay);
                    long current = retryDelay.toNanos();
                    long maximum = reconnectMaxInterval.toNanos();
                    retryDelay = Duration.ofNanos(current >= maximum / 2 ? maximum : current * 2);
                }
            }
            if (failure == null) {
                result.complete(null);
            } else {
                result.completeExceptionally(failure);
            }
        }
    }

    private void ensureOpen() {
        if (closed || !resources.isOpen()) {
            throw new BobaStrawConnectionException("Sentinel client is closed");
        }
    }

    @Override
    public void close() {
        NioConnection previous;
        Discovery active;
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            generation++;
            if (scheduled != null) {
                scheduled.cancel();
            }
            previous = master;
            master = null;
            masterEndpoint = null;
            active = discovery;
            discovery = null;
        }
        if (previous != null) {
            previous.closeForTopologyChange();
        }
        if (active != null) {
            if (active.query != null) {
                active.query.close();
            }
            if (active.candidate != null) {
                active.candidate.closeForTopologyChange();
            }
            active.result.completeExceptionally(new BobaStrawConnectionException("Sentinel client is closed"));
        }
        if (ownsResources) {
            resources.close();
        }
    }

    private static Throwable unwrap(Throwable error) {
        while ((error instanceof java.util.concurrent.CompletionException
            || error instanceof java.util.concurrent.ExecutionException) && error.getCause() != null) {
            error = error.getCause();
        }
        return error;
    }

    private static List<RespValue> array(RespValue value) {
        if (!(value instanceof RespValue.Array)) {
            throw new BobaStrawProtocolException("Expected Sentinel/ROLE array response");
        }
        return ((RespValue.Array) value).values;
    }

    private static void validateOrdinary(String command, String[] arguments) {
        CommandRegistry.requireOrdinary(command, CommandArgs.text(arguments));
    }

    private static final class Endpoint {
        private final String host;
        private final int port;

        private Endpoint(String host, int port) {
            if (host == null || host.trim().isEmpty() || port < 1 || port > 65535) {
                throw new IllegalArgumentException("Invalid Sentinel endpoint");
            }
            this.host = host;
            this.port = port;
        }

        private boolean same(Endpoint other) {
            return other != null && host.equals(other.host) && port == other.port;
        }

        @Override
        public String toString() {
            return "[" + host + "]:" + port;
        }
    }

    public static final class Builder {
        private final List<Endpoint> sentinels = new ArrayList<Endpoint>();
        private String masterName;
        private String sentinelUsername;
        private String sentinelPassword;
        private String username;
        private String password;
        private ProtocolVersion protocol = ProtocolVersion.AUTO;
        private Duration commandTimeout = Duration.ofSeconds(2);
        private Duration discoveryTimeout = Duration.ofMillis(500);
        private Duration refreshInterval = Duration.ofSeconds(1);
        private Duration reconnectInterval = Duration.ofMillis(200);
        private Duration reconnectMaxInterval = Duration.ofSeconds(5);
        private RespLimits respLimits = RespLimits.defaults();
        private BobaStrawConnectionLimits connectionLimits = BobaStrawConnectionLimits.defaults();
        private BobaStrawClientResources resources;

        public Builder sentinel(String host, int port) {
            Endpoint endpoint = new Endpoint(host, port);
            for (Endpoint existing : sentinels) {
                if (endpoint.same(existing)) {
                    return this;
                }
            }
            sentinels.add(endpoint);
            return this;
        }

        public Builder masterName(String value) {
            if (value == null || value.trim().isEmpty()) {
                throw new IllegalArgumentException("masterName is required");
            }
            masterName = value;
            return this;
        }

        public Builder sentinelCredentials(String user, String secret) {
            sentinelUsername = user;
            sentinelPassword = secret;
            return this;
        }

        public Builder credentials(String user, String secret) {
            username = user;
            password = secret;
            return this;
        }

        public Builder protocol(ProtocolVersion value) {
            protocol = value == null ? ProtocolVersion.AUTO : value;
            return this;
        }

        public Builder commandTimeout(Duration value) {
            commandTimeout = positive(value);
            return this;
        }

        public Builder discoveryTimeout(Duration value) {
            discoveryTimeout = positive(value);
            return this;
        }

        public Builder topologyRefreshInterval(Duration value) {
            refreshInterval = positive(value);
            return this;
        }

        public Builder reconnectInterval(Duration value) {
            reconnectInterval = positive(value);
            return this;
        }

        public Builder reconnectMaxInterval(Duration value) {
            reconnectMaxInterval = positive(value);
            return this;
        }

        public Builder resources(BobaStrawClientResources value) {
            resources = value;
            return this;
        }

        public Builder respLimits(RespLimits value) {
            if (value == null) {
                throw new IllegalArgumentException("respLimits must not be null");
            }
            respLimits = value;
            return this;
        }

        public Builder connectionLimits(BobaStrawConnectionLimits value) {
            if (value == null) {
                throw new IllegalArgumentException("connectionLimits must not be null");
            }
            connectionLimits = value;
            return this;
        }

        public BobaStrawSentinelClient build() {
            if (sentinels.isEmpty() || masterName == null) {
                throw new IllegalArgumentException("At least one Sentinel and a masterName are required");
            }
            if (reconnectMaxInterval.compareTo(reconnectInterval) < 0) {
                throw new IllegalArgumentException("reconnectMaxInterval is below reconnectInterval");
            }
            return new BobaStrawSentinelClient(this);
        }

        private static Duration positive(Duration value) {
            if (value == null || value.isZero() || value.isNegative()) {
                throw new IllegalArgumentException("Duration must be positive");
            }
            value.toNanos();
            return value;
        }
    }
}
