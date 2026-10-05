package io.github.susongyan.bobastraw;

import io.github.susongyan.bobastraw.protocol.RespValue;
import io.github.susongyan.bobastraw.internal.NioConnection;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Single-use MULTI/EXEC helper owning a dedicated connection lease.
 * Await WATCH/UNWATCH before starting another operation. Close abandoned helpers.
 * Cancellation destroys the lease; it does not prove EXEC was not executed.
 */
public final class BobaStrawTransaction implements AutoCloseable {
    private final BobaStrawClient client;
    private final NioConnection connection;
    private final List<String[]> commands = new ArrayList<String[]>();
    private boolean finished;
    private Operation<?> operation;
    private CompletableFuture<?> source;
    private final Object resultOwner = new Object();
    private java.util.function.BooleanSupplier topologyCurrent = () -> true;
    private java.util.function.Consumer<String[]> topologyKeys = command -> { };
    private BobaStrawScripts scripts;

    BobaStrawTransaction(BobaStrawClient client, NioConnection connection) {
        this.client = client;
        this.connection = connection;
        this.scripts = client.scripts();
    }

    BobaStrawTransaction bindTopology(java.util.function.BooleanSupplier current,
                                     java.util.function.Consumer<String[]> keys,
                                     BobaStrawScripts registry) {
        topologyCurrent = current;
        topologyKeys = keys;
        scripts = registry;
        return this;
    }

    public synchronized BobaStrawTransaction command(String name, String... arguments) {
        ensureOpen();
        if (name == null || name.isEmpty() || arguments == null) {
            throw new IllegalArgumentException("Transaction command and arguments are required");
        }
        CommandRegistry.requireTransaction(name, CommandArgs.text(arguments));
        String[] command = new String[arguments.length + 1];
        command[0] = name;
        for (int index = 0; index < arguments.length; index++) {
            if (arguments[index] == null) {
                throw new IllegalArgumentException("Transaction arguments must not be null");
            }
            command[index + 1] = arguments[index];
        }
        topologyKeys.accept(command);
        commands.add(command);
        return this;
    }

    public CompletionStage<RespValue> watch(String... keys) {
        if (keys == null || keys.length == 0) {
            throw new IllegalArgumentException("WATCH requires keys");
        }
        for (String key : keys) {
            if (key == null) {
                throw new IllegalArgumentException("WATCH keys must not be null");
            }
        }
        String[] command = new String[keys.length + 1];
        command[0] = "WATCH";
        System.arraycopy(keys, 0, command, 1, keys.length);
        topologyKeys.accept(command);
        return start(() -> connection.executeStateful(command), false,
            BobaStrawTransaction::ok);
    }

    public CompletionStage<RespValue> unwatch() {
        return start(() -> connection.executeStateful(new String[] {"UNWATCH"}), false,
            BobaStrawTransaction::ok);
    }

    /**
     * Awaits MULTI and QUEUED acknowledgements before sending EXEC.
     * A WATCH conflict retains the legacy empty-list result.
     * Errors inside the EXEC array remain individual RESP error values, not a rollback.
     */
    public CompletionStage<List<RespValue>> exec() {
        return exec(BobaStrawTransaction::arrayResult);
    }

    public BobaStrawBatchCommands typed() {
        return new BobaStrawBatchCommands(this::enqueue, scripts);
    }

    private synchronized <T> BobaStrawCommandHandle<T> enqueue(TypedCommand<T> command) {
        int index = commands.size();
        command(command.name(), command.arguments());
        return new BobaStrawCommandHandle<T>(resultOwner, index, command.decoder());
    }

    /** Preserves WATCH abort separately from a successful empty EXEC array. */
    public CompletionStage<BobaStrawBatchResult> execTyped() {
        return exec(value -> new BobaStrawBatchResult(resultOwner, arrayResult(value), commands.size(),
            value instanceof RespValue.Null));
    }

    private <T> CompletionStage<T> exec(Function<RespValue, T> mapper) {
        return start(() -> {
            CompletionStage<RespValue> chain = executeStep(new String[] {"MULTI"})
                .thenApply(BobaStrawTransaction::ok);
            for (String[] command : commands) {
                chain = chain.thenCompose(ignored -> executeStep(command)).thenApply(value -> {
                    if (!"QUEUED".equals(value.asString())) {
                        throw new BobaStrawProtocolException("Expected QUEUED in transaction");
                    }
                    return value;
                });
            }
            return chain.thenCompose(ignored -> executeStep(new String[] {"EXEC"}));
        }, true, mapper);
    }

    private synchronized CompletionStage<RespValue> executeStep(String[] command) {
        if (finished) {
            CompletableFuture<RespValue> cancelled = new CompletableFuture<RespValue>();
            cancelled.cancel(false);
            return cancelled;
        }
        return connection.executeStateful(command);
    }

    /**
     * Abandons locally queued commands and waits for UNWATCH before reusing the connection.
     * MULTI is only sent by exec(), so sending DISCARD here would be invalid on Redis.
     */
    public CompletionStage<RespValue> discard() {
        return start(() -> connection.executeStateful(new String[] {"UNWATCH"}), true,
            BobaStrawTransaction::ok);
    }

    private <S, T> CompletionStage<T> start(
        Supplier<CompletionStage<S>> action, boolean terminal, Function<S, T> mapper
    ) {
        final Operation<T> result;
        final CompletionStage<S> submitted;
        synchronized (this) {
            ensureOpen();
            result = new Operation<T>();
            operation = result;
            try {
                submitted = action.get();
                source = submitted.toCompletableFuture();
            } catch (RuntimeException error) {
                operation = null;
                finished = true;
                client.releaseTransaction(connection, false);
                throw error;
            }
        }
        submitted.whenComplete((value, error) -> {
            T mapped = null;
            Throwable failure = error;
            if (failure == null) {
                try {
                    mapped = mapper.apply(value);
                } catch (RuntimeException mappingError) {
                    failure = mappingError;
                }
            }
            synchronized (BobaStrawTransaction.this) {
                if (operation != result) {
                    return;
                }
                operation = null;
                source = null;
                if (terminal || failure != null) {
                    finished = true;
                    client.releaseTransaction(connection, failure == null);
                }
            }
            // Never run user continuations while holding the transaction monitor.
            if (failure == null) {
                result.complete(mapped);
            } else {
                result.completeExceptionally(failure);
            }
        });
        return result;
    }

    private boolean abort(Operation<?> expected) {
        CompletableFuture<?> pending;
        Operation<?> cancelled;
        synchronized (this) {
            if (finished || (expected != null && operation != expected)) {
                return false;
            }
            finished = true;
            cancelled = operation;
            operation = null;
            pending = source;
            source = null;
            client.releaseTransaction(connection, false);
        }
        if (pending != null) {
            pending.cancel(false);
        }
        if (cancelled != null) {
            cancelled.cancelLocally();
        }
        return true;
    }

    /** Destroys an unfinished lease; safe to call after successful EXEC or discard. */
    @Override
    public void close() {
        abort(null);
    }

    private void ensureOpen() {
        if (finished) {
            throw new IllegalStateException("Transaction has already finished");
        }
        if (operation != null) {
            throw new IllegalStateException("Await the current transaction operation first");
        }
        if (!topologyCurrent.getAsBoolean()) {
            finished = true;
            client.releaseTransaction(connection, false);
            throw new BobaStrawCommandNotSentException("Transaction topology binding is no longer current", null);
        }
    }

    private static RespValue ok(RespValue value) {
        if (!"OK".equals(value.asString())) {
            throw new BobaStrawProtocolException("Expected OK in transaction");
        }
        return value;
    }

    private static List<RespValue> arrayResult(RespValue value) {
        if (value instanceof RespValue.Null) {
            return new ArrayList<RespValue>();
        }
        if (!(value instanceof RespValue.Array)) {
            throw new BobaStrawProtocolException("EXEC returned " + value.getClass().getSimpleName());
        }
        return ((RespValue.Array) value).values;
    }

    private final class Operation<T> extends CompletableFuture<T> {
        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            return abort(this);
        }

        private boolean cancelLocally() {
            return super.cancel(false);
        }
    }
}
