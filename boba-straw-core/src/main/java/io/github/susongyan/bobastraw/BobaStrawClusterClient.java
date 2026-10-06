package io.github.susongyan.bobastraw;

import io.github.susongyan.bobastraw.internal.NioConnection;
import io.github.susongyan.bobastraw.internal.NioConnectionFactory;
import io.github.susongyan.bobastraw.protocol.RespLimits;
import io.github.susongyan.bobastraw.protocol.RespValue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Primary-only Redis Cluster routing. Only explicit MOVED/ASK replies permit one redirect;
 * uncertain failures are never replayed. Unknown commands require explicit key metadata.
 */
public final class BobaStrawClusterClient implements AutoCloseable {
    private final Duration timeout;
    private final Duration refreshInterval;
    private final Duration reconnectInterval;
    private final Duration reconnectMaxInterval;
    private final ProtocolVersion protocol;
    private final String username;
    private final String password;
    private final String clientName;
    private final BobaStrawClientResources resources;
    private final boolean ownsResources;
    private final NioConnectionFactory connectionFactory;
    private final BobaStrawTlsOptions tlsOptions;
    private final RespLimits respLimits;
    private final BobaStrawConnectionLimits connectionLimits;
    private final List<Seed> seeds;
    private final int maxRedirectConnections;
    private final Map<String, Node> nodes = new LinkedHashMap<String, Node>();
    private final Set<NioConnection> redirectConnections = new HashSet<NioConnection>();
    private final Object lock = new Object();
    private Node[] slots = new Node[16384];
    private boolean closed;
    private long topologyVersion;
    private long dedicatedGeneration;
    private long refreshGeneration;
    private long refreshSuccesses;
    private long refreshFailures;
    private CompletableFuture<Void> refreshing;
    private boolean refreshAgain;
    private boolean eventRefreshScheduled;
    private NioConnectionFactory.ScheduledTask refreshTask;
    private final BobaStrawScripts scripts;

    private BobaStrawClusterClient(Builder builder) {
        this.ownsResources = builder.resources == null;
        this.resources = ownsResources ? BobaStrawClientResources.builder().build() : builder.resources;
        if (!resources.isOpen()) {
            throw new BobaStrawConnectionException("Boba Straw client resources are closed");
        }
        this.tlsOptions = builder.tlsOptions;
        this.connectionFactory = resources.connectionFactory().withTls(tlsOptions);
        this.respLimits = builder.respLimits;
        this.connectionLimits = builder.connectionLimits;
        this.timeout = builder.timeout;
        this.refreshInterval = builder.refreshInterval;
        this.reconnectInterval = builder.reconnectInterval;
        this.reconnectMaxInterval = builder.reconnectMaxInterval;
        this.maxRedirectConnections = builder.maxRedirectConnections;
        this.protocol = builder.protocol;
        this.username = builder.username;
        this.password = builder.password;
        this.clientName = builder.clientName;
        this.seeds = new ArrayList<Seed>(builder.seeds);
        this.scripts = new BobaStrawScripts(new BobaStrawScripts.Router() {
            @Override
            public BobaStrawScripts.Target select(byte[][] keys) {
                Integer slot = scriptSlot(keys);
                synchronized (lock) {
                    ensureOpen();
                    return (slot == null ? anyPrimary() : slots[slot.intValue()]).client.scriptTarget();
                }
            }

            @Override
            public BobaStrawScripts.Target redirect(
                BobaStrawScripts.Target source, byte[][] keys, Throwable error
            ) {
                return redirectScript(source, scriptSlot(keys), error);
            }
        }, timeout, true, builder.scriptOptions);
        try {
            bootstrap();
            synchronized (lock) {
                scheduleRefresh(refreshInterval);
            }
        } catch (RuntimeException error) {
            close();
            throw error;
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Typed ordinary String commands, with the same slot checks and redirects as the Raw API. */
    public BobaStrawAsyncCommands async() {
        return new BobaStrawAsyncCommands(this::executeAsync, command -> executeBlocking(command, false));
    }

    private CompletionStage<RespValue> executeBlocking(String[] command, boolean transport) {
        Integer slot = ClusterCommandRouting.sameSlot(
            java.util.Arrays.copyOfRange(command, 1, command.length - 1));
        synchronized (lock) {
            ensureOpen();
            CompletionStage<RespValue> result = slots[slot.intValue()].client.executeBlocking(command, transport);
            result.whenComplete((value, error) -> {
                if (error != null) {
                    requestRefresh();
                }
            });
            return result;
        }
    }

    /** Classic Pub/Sub on one primary, not sharded Pub/Sub. Re-subscription is explicit. */
    public BobaStrawPubSub pubSub() {
        return new BobaStrawPubSub(listener -> {
            synchronized (lock) {
                ensureOpen();
                BobaStrawClient owner = anyPrimary().client;
                return new BobaStrawPubSub.Binding(owner, owner.openPubSubConnection(listener));
            }
        });
    }

    /** All WATCH and command keys must use the routing key's slot; no transaction replay. */
    public BobaStrawTransaction transaction(String routingKey) {
        CommandArgs.text(routingKey);
        final int slot = ClusterSlot.of(routingKey);
        final Node selected;
        final long selectedGeneration;
        synchronized (lock) {
            ensureOpen();
            selected = slots[slot];
            selectedGeneration = dedicatedGeneration;
        }
        BobaStrawScripts.Target generation = selected.client.scriptTarget();
        return selected.client.transaction().bindTopology(() -> {
            synchronized (lock) {
                return !closed && dedicatedGeneration == selectedGeneration
                    && slots[slot] == selected && generation.current.getAsBoolean();
            }
        }, command -> {
            Integer actual = "WATCH".equals(command[0])
                ? ClusterCommandRouting.sameSlot(tail(command))
                : ClusterCommandRouting.slot(command[0], tail(command));
            if (actual != null && actual.intValue() != slot) {
                throw new IllegalArgumentException("CROSSSLOT: transaction keys must match the routing slot");
            }
        }, scripts);
    }

    private void replaceSlots(Node[] replacement) {
        if (!java.util.Arrays.equals(slots, replacement)) {
            dedicatedGeneration++;
            // Conservative retirement: never continue stateful work across a changed slot snapshot.
            for (Node node : nodes.values()) {
                node.client.retireDedicatedForTopologyChange();
            }
        }
        slots = replacement;
    }

    /** One-slot batch; MOVED/ASK remain errors and never reorder or replay the batch. */
    public BobaStrawPipeline pipeline() {
        return new BobaStrawPipeline((commands, retainErrors) -> {
            Integer slot = null;
            for (String[] command : commands) {
                Integer next = ClusterCommandRouting.slot(command[0], tail(command));
                if (next != null) {
                    if (slot != null && !slot.equals(next)) {
                        throw new IllegalArgumentException("CROSSSLOT: pipeline keys must share one slot");
                    }
                    slot = next;
                }
            }
            final Node target;
            synchronized (lock) {
                ensureOpen();
                target = slot == null ? anyPrimary() : slots[slot.intValue()];
            }
            CompletionStage<List<RespValue>> result = target.client.executeBatch(commands, retainErrors);
            result.whenComplete((values, error) -> {
                if (error != null) {
                    requestRefresh();
                } else {
                    for (RespValue value : values) {
                        if (value instanceof RespValue.Error || value instanceof RespValue.BlobError) {
                            requestRefresh();
                            break;
                        }
                    }
                }
            });
            return result;
        }, scripts);
    }

    /** Ordinary String calls wait for transport completion, including MOVED/ASK handling. */
    public BobaStrawSyncCommands sync() {
        return new BobaStrawSyncCommands((command, arguments) ->
            execute(ClusterCommandRouting.slot(command, arguments), command, arguments, true),
            command -> executeBlocking(command, true));
    }

    /** Binary-safe ordinary commands, routed using the original bytes of every key. */
    public BobaStrawBinaryCommands binary() {
        return BobaStrawBinaryCommands.withExecutor(new BinaryCommandExecutor() {
            @Override
            public CompletionStage<RespValue> executeAsync(
                io.github.susongyan.bobastraw.internal.EncodedCommand command
            ) {
                throw new IllegalArgumentException("Cluster binary calls require key metadata");
            }

            @Override
            public CompletionStage<RespValue> executeCommand(TypedCommand<?> command) {
                Integer slot = command.binaryClusterSlot();
                final Node target;
                synchronized (lock) {
                    ensureOpen();
                    target = slot == null ? anyPrimary() : slots[slot.intValue()];
                }
                CommandFuture result = new CommandFuture(slot, null, command.binaryFrame());
                result.send(target, 0);
                return result;
            }
        });
    }

    public BobaStrawScripts scripts() {
        return scripts;
    }

    private static Integer scriptSlot(byte[][] keys) {
        return ClusterCommandRouting.sameSlot(CommandArgs.binary(keys));
    }

    private BobaStrawScripts.Target redirectScript(
        BobaStrawScripts.Target source, Integer slot, Throwable failure
    ) {
        String message = failure.getMessage() == null ? "" : failure.getMessage();
        if (!(failure instanceof BobaStrawServerException) || message.startsWith("MOVED ")
            || message.startsWith("ASK ") || message.startsWith("CLUSTERDOWN ")
            || message.startsWith("READONLY ") || message.startsWith("TRYAGAIN ")) {
            requestRefresh();
        }
        if (!(failure instanceof BobaStrawServerException) || slot == null
            || (!message.startsWith("MOVED ") && !message.startsWith("ASK "))) {
            return null;
        }
        String[] parts = message.split(" ");
        if (parts.length != 3 || Integer.parseInt(parts[1]) != slot.intValue()) {
            return null;
        }
        String host = source.connection.host();
        host = host.indexOf(':') >= 0 ? "[" + host + "]" : host;
        Seed endpoint = Builder.parseEndpoint(parts[2].startsWith(":") ? host + parts[2] : parts[2]);
        synchronized (lock) {
            ensureOpen();
            if ("MOVED".equals(parts[0])) {
                Node destination = node(endpoint);
                Node[] replacement = slots.clone();
                replacement[slot.intValue()] = destination;
                replaceSlots(replacement);
                topologyVersion++;
                return destination.client.scriptTarget();
            }
            if (redirectConnections.size() >= maxRedirectConnections) {
                throw new BobaStrawBackpressureException("Cluster ASK connection limit reached");
            }
            final NioConnection dedicated = connectionFactory.create(
                endpoint.host, endpoint.port, timeout, protocol, username, password,
                clientName, null, Duration.ZERO, respLimits, connectionLimits);
            redirectConnections.add(dedicated);
            dedicated.onClose(() -> {
                synchronized (lock) {
                    redirectConnections.remove(dedicated);
                }
            });
            return new BobaStrawScripts.Target(dedicated, dedicated::isOpen, true);
        }
    }

    /** Key-bound scans only; database SCAN is rejected by this facade. */
    public BobaStrawScanCommands scan() {
        return new BobaStrawScanCommands(this::executeAsync, false);
    }

    public CompletionStage<RespValue> executeAsync(String command, String... arguments) {
        return execute(ClusterCommandRouting.slot(command, arguments), command, arguments);
    }

    /**
     * Loads on the current primary for routingKey, without executing or broadcasting.
     * The routing key is not sent as a SCRIPT LOAD argument. A subsequent topology change
     * can still make EVALSHA fail with NOSCRIPT; no preload is automatically repeated.
     */
    public CompletionStage<String> scriptLoadForKey(String routingKey, String script) {
        CommandArgs.text(routingKey);
        TypedCommand<String> command = ScriptCommandFactory.load(script);
        return BobaStrawStages.map(execute(ClusterSlot.of(routingKey), command.name(), command.arguments()),
            command.decoder()::apply);
    }

    /**
     * Raw ordinary command escape hatch. Supply ALL keys; an empty list selects one primary,
     * not all nodes. The caller owns metadata correctness for commands unknown to this version.
     */
    public CompletionStage<RespValue> executeWithKeysAsync(
        String[] keys, String command, String... arguments
    ) {
        return execute(ClusterCommandRouting.explicitSlot(keys, command, arguments), command, arguments);
    }

    private CompletionStage<RespValue> execute(Integer slot, String command, String[] arguments) {
        return execute(slot, command, arguments, false);
    }

    private CompletionStage<RespValue> execute(Integer slot, String command, String[] arguments,
                                               boolean transportCompletion) {
        final Node target;
        synchronized (lock) {
            ensureOpen();
            target = slot == null ? anyPrimary() : slots[slot.intValue()];
        }
        CommandFuture result = new CommandFuture(slot, join(command, arguments));
        result.transportCompletion = transportCompletion;
        result.send(target, 0);
        return result;
    }

    /** Refreshes an atomic slot snapshot; cancelling a caller's view does not cancel a shared refresh. */
    public CompletionStage<Void> refreshTopology() {
        return resources.exposeCompletion(refreshInternal());
    }

    private CompletionStage<Void> refreshInternal() {
        CompletableFuture<Void> result;
        List<Seed> candidates;
        long version;
        synchronized (lock) {
            ensureOpen();
            if (refreshing != null) {
                return refreshing.thenApply(ignored -> null);
            }
            if (refreshTask != null) {
                refreshTask.cancel();
                refreshTask = null;
            }
            refreshGeneration++;
            eventRefreshScheduled = false;
            result = new CompletableFuture<Void>();
            refreshing = result;
            candidates = candidates();
            version = topologyVersion;
        }
        refreshFrom(candidates, 0, version, result, null);
        return result.thenApply(ignored -> null);
    }

    /** Non-I/O snapshots of known node lifecycles, including configured discovery seeds. */
    public Map<String, BobaStrawClientMetrics> nodeMetrics() {
        List<Node> snapshot;
        synchronized (lock) {
            snapshot = new ArrayList<Node>(nodes.values());
        }
        Map<String, BobaStrawClientMetrics> result = new LinkedHashMap<String, BobaStrawClientMetrics>();
        for (Node node : snapshot) {
            result.put(node.endpoint.id(), node.client.metrics());
        }
        return Collections.unmodifiableMap(result);
    }

    public long topologyVersion() {
        synchronized (lock) {
            return topologyVersion;
        }
    }

    public long topologyRefreshSuccesses() {
        synchronized (lock) {
            return refreshSuccesses;
        }
    }

    public long topologyRefreshFailures() {
        synchronized (lock) {
            return refreshFailures;
        }
    }

    private void bootstrap() {
        RuntimeException last = null;
        List<Seed> initial = new ArrayList<Seed>(seeds);
        Collections.shuffle(initial);
        for (Seed seed : initial) {
            try {
                Node candidate = node(seed);
                // Bootstrap is synchronous, but must not wait on application callback workers.
                RespValue value = candidate.client.executeTransport("CLUSTER", "SLOTS")
                    .toCompletableFuture().join();
                install(value, seed, -1);
                return;
            } catch (RuntimeException error) {
                last = error;
            }
        }
        throw new BobaStrawConnectionException("Could not discover Redis Cluster slots from any seed", last);
    }

    private void refreshFrom(
        List<Seed> candidates, int index, long version, CompletableFuture<Void> result, Throwable last
    ) {
        if (result.isDone()) {
            return;
        }
        if (index == candidates.size()) {
            finishRefresh(result, new BobaStrawConnectionException("Cluster topology refresh failed", last));
            return;
        }
        Seed seed = candidates.get(index);
        CompletionStage<RespValue> request;
        try {
            request = node(seed).client.executeAsync("CLUSTER", "SLOTS");
        } catch (RuntimeException error) {
            finishRefresh(result, error);
            return;
        }
        request.whenComplete((value, error) -> {
            Throwable failure = error;
            if (failure == null) {
                try {
                    boolean installed = install(value, seed, version);
                    synchronized (lock) {
                        refreshAgain |= !installed;
                    }
                    finishRefresh(result, null);
                    return;
                } catch (RuntimeException invalid) {
                    failure = invalid;
                }
            }
            refreshFrom(candidates, index + 1, version, result, failure);
        });
    }

    private void finishRefresh(CompletableFuture<Void> result, Throwable failure) {
        synchronized (lock) {
            if (refreshing != result) {
                return;
            }
            refreshing = null;
            if (failure == null) {
                refreshSuccesses++;
            } else {
                refreshFailures++;
            }
            if (!closed && resources.isOpen()) {
                scheduleRefresh(refreshAgain ? Duration.ofMillis(250) : refreshInterval);
            }
            refreshAgain = false;
        }
        if (failure == null) {
            result.complete(null);
        } else {
            result.completeExceptionally(failure);
        }
    }

    private void requestRefresh() {
        synchronized (lock) {
            if (closed || !resources.isOpen()) {
                return;
            }
            if (refreshing != null) {
                refreshAgain = true;
                return;
            }
            // One debounced event refresh, rather than one discovery per failed application call.
            if (eventRefreshScheduled) {
                return;
            }
            if (refreshTask != null) {
                refreshTask.cancel();
            }
            eventRefreshScheduled = true;
            scheduleRefresh(Duration.ofMillis(250));
        }
    }

    private void scheduleRefresh(Duration delay) {
        final long generation = ++refreshGeneration;
        refreshTask = connectionFactory.schedule(() -> {
            synchronized (lock) {
                if (generation != refreshGeneration) {
                    return;
                }
                refreshTask = null;
                eventRefreshScheduled = false;
                if (closed || !resources.isOpen()) {
                    return;
                }
            }
            try {
                refreshInternal();
            } catch (BobaStrawConnectionException ignored) {
                // A concurrent close owns cleanup.
            }
        }, delay);
    }

    private List<Seed> candidates() {
        Map<String, Seed> unique = new LinkedHashMap<String, Seed>();
        for (Node node : slots) {
            if (node != null) {
                unique.put(node.endpoint.id(), node.endpoint);
            }
        }
        for (Seed seed : seeds) {
            unique.put(seed.id(), seed);
        }
        List<Seed> result = new ArrayList<Seed>(unique.values());
        Collections.shuffle(result);
        return result;
    }

    /** Parses entirely before changing live state. Invalid/partial maps never replace a usable map. */
    private boolean install(RespValue value, Seed source, long expectedVersion) {
        Seed[] endpoints = new Seed[16384];
        for (RespValue rangeValue : array(value)) {
            List<RespValue> range = array(rangeValue);
            if (range.size() < 3) {
                throw new BobaStrawProtocolException("Cluster slot range is incomplete");
            }
            long first = range.get(0).asLong();
            long last = range.get(1).asLong();
            if (first < 0 || last < first || last >= endpoints.length) {
                throw new BobaStrawProtocolException("Invalid Cluster slot range");
            }
            List<RespValue> address = array(range.get(2));
            if (address.size() < 2) {
                throw new BobaStrawProtocolException("Cluster primary endpoint is incomplete");
            }
            String host = address.get(0).asString();
            if (host == null || host.isEmpty()) {
                host = source.host;
            }
            long port = address.get(1).asLong();
            if ("?".equals(host) || port < 1 || port > 65535) {
                throw new BobaStrawProtocolException("Invalid Cluster primary endpoint");
            }
            Seed endpoint = new Seed(host, (int) port);
            for (int slot = (int) first; slot <= last; slot++) {
                if (endpoints[slot] != null) {
                    throw new BobaStrawProtocolException("Overlapping Cluster slot ranges");
                }
                endpoints[slot] = endpoint;
            }
        }
        for (Seed endpoint : endpoints) {
            if (endpoint == null) {
                throw new BobaStrawProtocolException("Cluster topology does not cover all 16384 slots");
            }
        }

        List<Node> retired = new ArrayList<Node>();
        synchronized (lock) {
            ensureOpen();
            if (expectedVersion >= 0 && topologyVersion != expectedVersion) {
                return false; // A newer MOVED reply must not be overwritten by an older refresh.
            }
            Node[] replacement = new Node[16384];
            Set<String> retained = new HashSet<String>();
            for (Seed seed : seeds) {
                retained.add(seed.id());
            }
            for (int slot = 0; slot < replacement.length; slot++) {
                replacement[slot] = node(endpoints[slot]);
                retained.add(endpoints[slot].id());
            }
            replaceSlots(replacement);
            topologyVersion++;
            java.util.Iterator<Map.Entry<String, Node>> iterator = nodes.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<String, Node> entry = iterator.next();
                if (!retained.contains(entry.getKey())) {
                    retired.add(entry.getValue());
                    iterator.remove();
                }
            }
        }
        // Retiring a removed primary never replays its in-flight commands.
        for (Node node : retired) {
            node.client.retireForTopologyChange();
        }
        return true;
    }

    private Node node(Seed endpoint) {
        synchronized (lock) {
            ensureOpen();
            Node existing = nodes.get(endpoint.id());
            if (existing != null) {
                return existing;
            }
            BobaStrawClient client = BobaStrawClient.builder()
                .endpoint(endpoint.host, endpoint.port).resources(resources)
                .tls(tlsOptions)
                .protocol(protocol).credentials(username, password).clientName(clientName)
                .commandTimeout(timeout).respLimits(respLimits).connectionLimits(connectionLimits)
                .reconnectInterval(reconnectInterval).reconnectMaxInterval(reconnectMaxInterval).build();
            Node created = new Node(endpoint, client);
            nodes.put(endpoint.id(), created);
            return created;
        }
    }

    private Node anyPrimary() {
        Node fallback = slots[0];
        Set<Node> inspected = new HashSet<Node>();
        for (Node node : slots) {
            if (node != null && inspected.add(node)
                && node.client.metrics().sharedConnectionState() == BobaStrawConnectionState.READY) {
                return node;
            }
        }
        return fallback;
    }

    private void ensureOpen() {
        if (closed || !resources.isOpen()) {
            throw new BobaStrawConnectionException("Cluster client is closed");
        }
    }

    private final class CommandFuture extends CompletableFuture<RespValue> {
        private final Integer slot;
        private final String[] command;
        private final io.github.susongyan.bobastraw.internal.EncodedCommand binaryCommand;
        private CompletableFuture<?> pending;
        private NioConnection asking;
        private boolean terminal;
        private boolean transportCompletion;

        private CommandFuture(Integer slot, String[] command) {
            this(slot, command, null);
        }

        private CommandFuture(Integer slot, String[] command,
                              io.github.susongyan.bobastraw.internal.EncodedCommand binaryCommand) {
            this.slot = slot;
            this.command = command;
            this.binaryCommand = binaryCommand;
        }

        private void send(Node target, int redirects) {
            CompletionStage<RespValue> stage;
            try {
                synchronized (this) {
                    if (terminal) {
                        return;
                    }
                    stage = binaryCommand != null ? target.client.executeEncodedCommand(binaryCommand)
                        : transportCompletion ? target.client.executeTransport(command[0], tail(command))
                        : target.client.executeAsync(command[0], tail(command));
                    pending = stage.toCompletableFuture();
                }
            } catch (RuntimeException error) {
                finish(null, error);
                return;
            }
            stage.whenComplete((value, error) -> {
                if (error == null) {
                    finish(value, null);
                } else {
                    redirect(target.endpoint, redirects, error);
                }
            });
        }

        private void redirect(Seed source, int redirects, Throwable error) {
            Throwable cause = unwrap(error);
            String message = cause.getMessage() == null ? "" : cause.getMessage();
            if (!(cause instanceof BobaStrawServerException) || message.startsWith("MOVED ")
                || message.startsWith("ASK ") || message.startsWith("CLUSTERDOWN ")
                || message.startsWith("READONLY ") || message.startsWith("TRYAGAIN ")) {
                requestRefresh();
            }
            if (!(cause instanceof BobaStrawServerException) || redirects >= 1 || slot == null
                || (!message.startsWith("MOVED ") && !message.startsWith("ASK "))) {
                finish(null, error);
                return;
            }
            try {
                String[] parts = message.split(" ");
                if (parts.length != 3 || Integer.parseInt(parts[1]) != slot.intValue()) {
                    finish(null, error);
                    return;
                }
                String host = source.host.indexOf(':') >= 0 ? "[" + source.host + "]" : source.host;
                String address = parts[2].startsWith(":") ? host + parts[2] : parts[2];
                Seed endpoint = Builder.parseEndpoint(address);
                if ("ASK".equals(parts[0])) {
                    ask(endpoint);
                    return;
                }
                Node destination;
                synchronized (this) {
                    if (terminal) {
                        return;
                    }
                    synchronized (lock) {
                        destination = node(endpoint);
                        Node[] replacement = slots.clone();
                        replacement[slot.intValue()] = destination;
                        replaceSlots(replacement);
                        topologyVersion++;
                    }
                }
                send(destination, redirects + 1);
            } catch (RuntimeException invalid) {
                finish(null, invalid);
            }
        }

        private void ask(Seed endpoint) {
            CompletionStage<RespValue> stage;
            synchronized (this) {
                if (terminal) {
                    return;
                }
                synchronized (lock) {
                    ensureOpen();
                    if (redirectConnections.size() >= maxRedirectConnections) {
                        throw new BobaStrawBackpressureException("Cluster ASK connection limit reached");
                    }
                    asking = connectionFactory.create(
                        endpoint.host, endpoint.port, timeout, protocol, username, password,
                        clientName, null, Duration.ZERO, respLimits, connectionLimits
                    );
                    redirectConnections.add(asking);
                    final NioConnection dedicated = asking;
                    dedicated.onClose(() -> {
                        synchronized (lock) {
                            redirectConnections.remove(dedicated);
                        }
                    });
                }
                stage = transportCompletion ? asking.executeTransport(new String[] {"ASKING"})
                    : asking.executeStateful(new String[] {"ASKING"});
                pending = stage.toCompletableFuture();
            }
            stage.whenComplete((value, error) -> {
                if (error != null) {
                    finish(null, error);
                    return;
                }
                CompletionStage<RespValue> response;
                try {
                    synchronized (this) {
                        if (terminal) {
                            return;
                        }
                        if (!"OK".equals(value.asString())) {
                            throw new BobaStrawProtocolException("ASKING did not return OK");
                        }
                        response = binaryCommand == null ? asking.executeDedicated(command, transportCompletion)
                            : asking.executeEncodedCommand(binaryCommand);
                        pending = response.toCompletableFuture();
                    }
                    response.whenComplete(this::finish);
                } catch (RuntimeException failure) {
                    finish(null, failure);
                }
            });
        }

        private void finish(RespValue value, Throwable error) {
            NioConnection dedicated;
            synchronized (this) {
                if (terminal) {
                    return;
                }
                terminal = true;
                pending = null;
                dedicated = asking;
                asking = null;
            }
            if (dedicated != null) {
                dedicated.close();
            }
            if (error == null) {
                complete(value);
            } else {
                completeExceptionally(error);
            }
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            CompletableFuture<?> active;
            NioConnection dedicated;
            synchronized (this) {
                if (terminal) {
                    return false;
                }
                terminal = true;
                active = pending;
                dedicated = asking;
                pending = null;
                asking = null;
            }
            if (dedicated != null) {
                dedicated.close();
            }
            if (active != null) {
                active.cancel(false);
            }
            return super.cancel(false);
        }
    }

    private static Throwable unwrap(Throwable error) {
        while ((error instanceof java.util.concurrent.CompletionException
            || error instanceof java.util.concurrent.ExecutionException) && error.getCause() != null) {
            error = error.getCause();
        }
        return error;
    }

    private static String[] join(String command, String[] arguments) {
        String[] result = new String[arguments.length + 1];
        result[0] = command;
        System.arraycopy(arguments, 0, result, 1, arguments.length);
        return result;
    }

    private static String[] tail(String[] command) {
        return java.util.Arrays.copyOfRange(command, 1, command.length);
    }

    private static List<RespValue> array(RespValue value) {
        if (!(value instanceof RespValue.Array)) {
            throw new BobaStrawProtocolException("Expected Cluster array response");
        }
        return ((RespValue.Array) value).values;
    }

    @Override
    public void close() {
        scripts.close();
        List<Node> closing;
        List<NioConnection> dedicated;
        CompletableFuture<Void> refresh;
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            refreshGeneration++;
            if (refreshTask != null) {
                refreshTask.cancel();
                refreshTask = null;
            }
            closing = new ArrayList<Node>(nodes.values());
            dedicated = new ArrayList<NioConnection>(redirectConnections);
            refresh = refreshing;
            refreshing = null;
            nodes.clear();
            redirectConnections.clear();
            slots = new Node[16384];
        }
        for (Node node : closing) {
            node.client.close();
        }
        for (NioConnection connection : dedicated) {
            connection.close();
        }
        if (refresh != null) {
            refresh.completeExceptionally(new BobaStrawConnectionException("Cluster client is closed"));
        }
        if (ownsResources) {
            resources.close();
        }
    }

    private static final class Node {
        private final Seed endpoint;
        private final BobaStrawClient client;

        private Node(Seed endpoint, BobaStrawClient client) {
            this.endpoint = endpoint;
            this.client = client;
        }
    }

    private static final class Seed {
        private final String host;
        private final int port;

        private Seed(String host, int port) {
            this.host = host;
            this.port = port;
        }

        private String id() {
            return "[" + host + "]:" + port;
        }
    }

    public static final class Builder {
        private BobaStrawTlsOptions tlsOptions;

        /** Applies TLS to seeds, discovered nodes, redirects and dedicated connections. */
        public Builder tls(BobaStrawTlsOptions value) {
            tlsOptions = value;
            return this;
        }

        private final List<Seed> seeds = new ArrayList<Seed>();
        private boolean explicitSeeds;
        private Duration timeout = Duration.ofSeconds(2);
        private Duration refreshInterval = Duration.ofSeconds(30);
        private Duration reconnectInterval = Duration.ofSeconds(1);
        private Duration reconnectMaxInterval = Duration.ofSeconds(30);
        private int maxRedirectConnections = 32;
        private ProtocolVersion protocol = ProtocolVersion.AUTO;
        private String username;
        private String password;
        private String clientName;
        private BobaStrawClientResources resources;
        private RespLimits respLimits = RespLimits.defaults();
        private BobaStrawConnectionLimits connectionLimits = BobaStrawConnectionLimits.defaults();
        private BobaStrawScriptOptions scriptOptions = BobaStrawScriptOptions.defaults();

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
            positive(value, "commandTimeout");
            this.timeout = value;
            return this;
        }

        /** Periodic discovery continues even when there is no application traffic. */
        public Builder topologyRefreshInterval(Duration value) {
            positive(value, "topologyRefreshInterval");
            this.refreshInterval = value;
            return this;
        }

        public Builder reconnectInterval(Duration value) {
            positive(value, "reconnectInterval");
            this.reconnectInterval = value;
            return this;
        }

        public Builder reconnectMaxInterval(Duration value) {
            positive(value, "reconnectMaxInterval");
            this.reconnectMaxInterval = value;
            return this;
        }

        public Builder maxRedirectConnections(int value) {
            if (value < 1) {
                throw new IllegalArgumentException("maxRedirectConnections must be positive");
            }
            this.maxRedirectConnections = value;
            return this;
        }

        public Builder protocol(ProtocolVersion value) {
            this.protocol = value == null ? ProtocolVersion.AUTO : value;
            return this;
        }

        /** Sets inbound RESP resource limits for every Cluster node connection. */
        public Builder respLimits(RespLimits value) {
            if (value == null) {
                throw new IllegalArgumentException("respLimits must not be null");
            }
            this.respLimits = value;
            return this;
        }

        /** Sets command admission limits for every physical Cluster node connection. */
        /** Configures this client's local script registry, not the Redis server cache. */
        public Builder scriptOptions(BobaStrawScriptOptions value) {
            if (value == null) {
                throw new IllegalArgumentException("scriptOptions must not be null");
            }
            this.scriptOptions = value;
            return this;
        }

        public Builder connectionLimits(BobaStrawConnectionLimits value) {
            if (value == null) {
                throw new IllegalArgumentException("connectionLimits must not be null");
            }
            this.connectionLimits = value;
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

        /** Uses externally owned selector resources. Closing this client will not close them. */
        public Builder resources(BobaStrawClientResources value) {
            this.resources = value;
            return this;
        }

        public BobaStrawClusterClient build() {
            if (reconnectMaxInterval.compareTo(reconnectInterval) < 0) {
                throw new IllegalArgumentException("reconnectMaxInterval is below reconnectInterval");
            }
            return new BobaStrawClusterClient(this);
        }

        private static void positive(Duration value, String name) {
            if (value == null || value.isNegative() || value.isZero()) {
                throw new IllegalArgumentException(name + " must be positive");
            }
            value.toNanos();
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
