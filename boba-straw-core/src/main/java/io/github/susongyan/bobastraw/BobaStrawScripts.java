package io.github.susongyan.bobastraw;

import io.github.susongyan.bobastraw.internal.EncodedCommand;
import io.github.susongyan.bobastraw.internal.NioConnection;
import io.github.susongyan.bobastraw.protocol.RespValue;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.BooleanSupplier;

/**
 * Client-owned local script registry. First use on a physical connection sends EVAL;
 * a cache hint permits EVALSHA and one explicit NOSCRIPT-to-EVAL recovery.
 * Registered scripts MUST NOT manufacture or forward NOSCRIPT as a top-level business error.
 * Network failures and decoding failures are never replayed. Cancellation is not rollback.
 */
public final class BobaStrawScripts {
    private final Router router;
    private final long timeoutNanos;
    private final boolean binary;
    private final int maxDefinitions;
    private final long maxBytes;
    private final int maxHints;
    private final int maxInFlightExecutions;
    private final Map<String, Definition> definitions = new LinkedHashMap<String, Definition>();
    private final Map<HintKey, Object> hints = new LinkedHashMap<HintKey, Object>(16, 0.75f, true);
    private final Set<Operation<?>> operations = new HashSet<Operation<?>>();
    private long registeredBytes;
    private boolean closed;

    BobaStrawScripts(Router router, Duration timeout, boolean binary) {
        this(router, timeout, binary, BobaStrawScriptOptions.defaults());
    }

    BobaStrawScripts(Router router, Duration timeout, boolean binary,
                     int maxDefinitions, long maxBytes, int maxHints) {
        this(router, timeout, binary, BobaStrawScriptOptions.builder()
            .maxRegisteredScripts(maxDefinitions).maxScriptBytes(maxBytes).maxCacheHints(maxHints).build());
    }

    BobaStrawScripts(Router router, Duration timeout, boolean binary, BobaStrawScriptOptions options) {
        this.router = router;
        this.timeoutNanos = timeout.toNanos();
        this.binary = binary;
        this.maxDefinitions = options.maxRegisteredScripts();
        this.maxBytes = options.maxScriptBytes();
        this.maxHints = options.maxCacheHints();
        this.maxInFlightExecutions = options.maxInFlightExecutions();
    }

    /** Local only. Identical registration is idempotent; conflicting names are rejected. */
    public <T> void register(String name, String script, ScriptOutput<T> output) {
        if (script == null) {
            throw new IllegalArgumentException("Script is required");
        }
        register(name, script.getBytes(StandardCharsets.UTF_8), output);
    }

    /** Preserves exact script bytes, including whitespace; registration never contacts Redis. */
    public synchronized <T> void register(String name, byte[] script, ScriptOutput<T> output) {
        ensureOpen();
        if (name == null || name.isEmpty() || name.length() > 256 || script == null || output == null) {
            throw new IllegalArgumentException("Script name, body and output are required");
        }
        Definition previous = definitions.get(name);
        if (previous != null) {
            if (!Arrays.equals(previous.body, script) || !previous.output.equals(output)) {
                throw new IllegalArgumentException("Conflicting script registration: " + name);
            }
            return;
        }
        if (definitions.size() >= maxDefinitions || script.length > maxBytes - registeredBytes) {
            throw new BobaStrawBackpressureException("Local script registry capacity is exhausted");
        }
        Definition definition = new Definition(script.clone(), output);
        definitions.put(name, definition);
        registeredBytes += definition.body.length;
    }

    public <T> CompletionStage<T> execute(String name, ScriptOutput<T> output,
                                         String[] keys, String... arguments) {
        return executeSnapshot(name, output, utf8(keys), utf8(arguments));
    }

    /** All three topologies. Keys and arguments are snapshotted without a UTF-8 round trip. */
    public <T> CompletionStage<T> executeBinary(String name, ScriptOutput<T> output,
                                               byte[][] keys, byte[]... arguments) {
        if (!binary) {
            throw new UnsupportedOperationException("Topology binary scripts are not yet supported");
        }
        return executeSnapshot(name, output, snapshot(keys), snapshot(arguments));
    }

    <T> TypedCommand<T> batchCommand(String name, ScriptOutput<T> output,
                                    String[] keys, String[] arguments) {
        final Definition definition;
        synchronized (this) {
            ensureOpen();
            definition = definitions.get(name);
            if (definition == null || !definition.output.equals(output)) {
                throw new IllegalArgumentException("Unknown script or mismatched output: " + name);
            }
        }
        final String body;
        try {
            body = StandardCharsets.UTF_8.newDecoder()
                .decode(java.nio.ByteBuffer.wrap(definition.body)).toString();
        } catch (java.nio.charset.CharacterCodingException error) {
            throw new IllegalArgumentException("String batches require a UTF-8 script body", error);
        }
        return ScriptCommandFactory.text(false, body, output, keys, arguments);
    }

    private <T> CompletionStage<T> executeSnapshot(String name, ScriptOutput<T> output,
                                                  byte[][] keys, byte[][] arguments) {
        final Definition definition;
        synchronized (this) {
            ensureOpen();
            definition = definitions.get(name);
            if (definition == null || !definition.output.equals(output)) {
                throw new IllegalArgumentException("Unknown script or mismatched output: " + name);
            }
        }
        // Encoding a large script must not serialize unrelated registry lookups/completions.
        final Operation<T> operation = new Operation<T>(definition, output, keys, arguments);
        synchronized (this) {
            ensureOpen();
            if (operations.size() >= maxInFlightExecutions) {
                throw new BobaStrawBackpressureException("In-flight script capacity is exhausted");
            }
            operations.add(operation);
        }
        operation.start();
        return operation;
    }

    private synchronized Object hint(Target target, String sha) {
        pruneHints();
        return target.current.getAsBoolean() ? hints.get(new HintKey(target.connection, sha)) : null;
    }

    private synchronized void remember(Target target, String sha) {
        pruneHints();
        if (closed || !target.current.getAsBoolean() || !target.connection.isOpen() || target.asking) {
            return;
        }
        hints.put(new HintKey(target.connection, sha), new Object());
        while (hints.size() > maxHints) {
            hints.remove(hints.keySet().iterator().next());
        }
    }

    private synchronized void forget(Target target, String sha, Object observed) {
        HintKey key = new HintKey(target.connection, sha);
        if (hints.get(key) == observed) {
            hints.remove(key);
        }
    }

    private void pruneHints() {
        Iterator<HintKey> iterator = hints.keySet().iterator();
        while (iterator.hasNext()) {
            if (!iterator.next().connection.isOpen()) {
                iterator.remove();
            }
        }
    }

    void close() {
        ArrayList<Operation<?>> active;
        synchronized (this) {
            closed = true;
            definitions.clear();
            registeredBytes = 0;
            hints.clear();
            active = new ArrayList<Operation<?>>(operations);
        }
        for (Operation<?> operation : active) {
            operation.cancel(false);
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new BobaStrawConnectionException("Script registry is closed");
        }
    }

    interface Router {
        Target select(byte[][] keys);

        default Target redirect(Target source, byte[][] keys, Throwable failure) {
            return null;
        }
    }

    static final class Target {
        final NioConnection connection;
        final BooleanSupplier current;
        final boolean asking;

        Target(NioConnection connection, BooleanSupplier current, boolean asking) {
            this.connection = connection;
            this.current = current;
            this.asking = asking;
        }

        void release() {
            if (asking) {
                connection.close();
            }
        }
    }

    private final class Operation<T> extends CompletableFuture<T> {
        private final Definition definition;
        private final ScriptOutput<T> output;
        private final byte[][] keys;
        private final EncodedCommand eval;
        private final EncodedCommand evalSha;
        private final long deadline = System.nanoTime() + timeoutNanos;
        private Target target;
        private CompletableFuture<?> pending;
        private boolean terminal;
        private boolean recovered;
        private int redirects;

        Operation(Definition definition, ScriptOutput<T> output, byte[][] keys, byte[][] arguments) {
            this.definition = definition;
            this.output = output;
            this.keys = keys;
            this.eval = frame("EVAL", definition.body, keys, arguments);
            this.evalSha = frame("EVALSHA", definition.sha.getBytes(StandardCharsets.US_ASCII), keys, arguments);
        }

        void start() {
            try {
                synchronized (this) {
                    if (terminal) {
                        return;
                    }
                    target = router.select(keys);
                }
                send();
            } catch (RuntimeException error) {
                finish(null, error);
            }
        }

        private void send() {
            CompletionStage<RespValue> stage;
            final Object observed;
            final boolean bySha;
            try {
                synchronized (this) {
                    if (terminal) {
                        return;
                    }
                    checkDeadline();
                    observed = hint(target, definition.sha);
                    bySha = !recovered && observed != null;
                    stage = target.connection.executeEncodedCommand(target.asking
                        ? new EncodedCommand(new byte[][] {"ASKING".getBytes(StandardCharsets.US_ASCII)})
                        : bySha ? evalSha : eval, deadline);
                    pending = stage.toCompletableFuture();
                }
                stage.whenComplete((value, error) -> {
                    if (target.asking) {
                        if (error == null) {
                            sendAfterAsking(value, bySha, observed);
                        } else {
                            finish(null, askingFailure(error));
                        }
                    } else {
                        received(value, error, bySha, observed);
                    }
                });
            } catch (RuntimeException error) {
                finish(null, error);
            }
        }

        private void sendAfterAsking(RespValue acknowledgement, boolean bySha, Object observed) {
            try {
                CompletionStage<RespValue> stage;
                synchronized (this) {
                    if (terminal) {
                        return;
                    }
                    checkDeadline();
                    if (!"OK".equals(acknowledgement.asString())) {
                        throw new BobaStrawProtocolException("ASKING did not return OK");
                    }
                    stage = target.connection.executeEncodedCommand(bySha ? evalSha : eval, deadline);
                    pending = stage.toCompletableFuture();
                }
                stage.whenComplete((value, error) -> received(value, error, bySha, observed));
            } catch (RuntimeException error) {
                finish(null, error);
            }
        }

        private void received(RespValue value, Throwable error, boolean bySha, Object observed) {
            Throwable failure = unwrap(error);
            boolean again = false;
            try {
                synchronized (this) {
                    if (terminal) {
                        return;
                    }
                    if (failure == null) {
                        remember(target, definition.sha);
                    } else if (bySha && !recovered && noScript(failure)) {
                        forget(target, definition.sha, observed);
                        recovered = true;
                        again = true;
                    } else if (redirects == 0) {
                        Target replacement = router.redirect(target, keys, failure);
                        if (replacement != null) {
                            target.release();
                            target = replacement;
                            redirects++;
                            again = true;
                        }
                    }
                }
                if (again) {
                    send();
                } else {
                    finish(value, failure);
                }
            } catch (RuntimeException invalid) {
                finish(null, invalid);
            }
        }

        private void checkDeadline() {
            if (deadline - System.nanoTime() <= 0L) {
                throw new BobaStrawCommandTimeoutException(
                    "Script deadline expired before the next request was submitted", null, false);
            }
        }

        private void finish(RespValue value, Throwable error) {
            if (!detach()) {
                return;
            }
            if (error != null) {
                completeExceptionally(error);
                return;
            }
            try {
                complete(output.decode(value));
            } catch (RuntimeException invalid) {
                completeExceptionally(invalid);
            }
        }

        private boolean detach() {
            synchronized (this) {
                if (terminal) {
                    return false;
                }
                terminal = true;
                if (target != null) {
                    target.release();
                }
            }
            synchronized (BobaStrawScripts.this) {
                operations.remove(this);
            }
            return true;
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            if (!detach()) {
                return false;
            }
            CompletableFuture<?> active;
            synchronized (this) {
                active = pending;
            }
            if (active != null) {
                active.cancel(false);
            }
            return super.cancel(false);
        }
    }

    private static boolean noScript(Throwable error) {
        if (!(error instanceof BobaStrawServerException)) {
            return false;
        }
        String message = error.getMessage();
        return "NOSCRIPT".equals(message) || message != null && message.startsWith("NOSCRIPT ");
    }

    private static Throwable askingFailure(Throwable error) {
        Throwable cause = unwrap(error);
        if (cause instanceof BobaStrawCommandTimeoutException) {
            return new BobaStrawCommandTimeoutException(
                "ASKING timed out before the script was submitted", cause, false);
        }
        if (cause instanceof BobaStrawCommandMayHaveExecutedException) {
            return new BobaStrawCommandNotSentException(
                "ASKING failed before the script was submitted", cause);
        }
        return cause;
    }

    private static Throwable unwrap(Throwable error) {
        while ((error instanceof java.util.concurrent.CompletionException
            || error instanceof java.util.concurrent.ExecutionException) && error.getCause() != null) {
            error = error.getCause();
        }
        return error;
    }

    private static byte[][] utf8(String[] values) {
        CommandArgs.text(values);
        byte[][] result = new byte[values.length][];
        for (int index = 0; index < values.length; index++) {
            result[index] = values[index].getBytes(StandardCharsets.UTF_8);
        }
        return result;
    }

    private static byte[][] snapshot(byte[][] values) {
        CommandArgs.binary(values);
        byte[][] result = new byte[values.length][];
        for (int index = 0; index < values.length; index++) {
            result[index] = values[index].clone();
        }
        return result;
    }

    private static EncodedCommand frame(String command, byte[] source, byte[][] keys, byte[][] args) {
        byte[][] all = new byte[3 + keys.length + args.length][];
        all[0] = command.getBytes(StandardCharsets.US_ASCII);
        all[1] = source;
        all[2] = Integer.toString(keys.length).getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(keys, 0, all, 3, keys.length);
        System.arraycopy(args, 0, all, 3 + keys.length, args.length);
        CommandRegistry.requireOrdinary(command,
            CommandArgs.binary(Arrays.copyOfRange(all, 1, all.length)));
        return new EncodedCommand(all);
    }

    private static final class Definition {
        private final byte[] body;
        private final String sha;
        private final ScriptOutput<?> output;

        Definition(byte[] body, ScriptOutput<?> output) {
            this.body = body;
            this.output = output;
            try {
                byte[] digest = MessageDigest.getInstance("SHA-1").digest(body);
                StringBuilder text = new StringBuilder(40);
                for (byte value : digest) {
                    text.append(Character.forDigit((value & 255) >>> 4, 16));
                    text.append(Character.forDigit(value & 15, 16));
                }
                sha = text.toString();
            } catch (NoSuchAlgorithmException unavailable) {
                throw new IllegalStateException("JDK SHA-1 is unavailable", unavailable);
            }
        }
    }

    private static final class HintKey {
        private final NioConnection connection;
        private final String sha;

        HintKey(NioConnection connection, String sha) {
            this.connection = connection;
            this.sha = sha;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof HintKey && connection == ((HintKey) other).connection
                && sha.equals(((HintKey) other).sha);
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(connection) * 31 + sha.hashCode();
        }
    }
}
