package io.github.susongyan.bobastraw;

import io.github.susongyan.bobastraw.protocol.RespValue;
import io.github.susongyan.bobastraw.internal.NioConnection;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** MULTI/EXEC helper backed by a connection dedicated to this transaction. */
public final class BobaStrawTransaction {
    private final BobaStrawClient client;
    private final NioConnection connection;
    private final List<String[]> commands = new ArrayList<String[]>();
    private boolean finished;

    BobaStrawTransaction(BobaStrawClient client, NioConnection connection) {
        this.client = client;
        this.connection = connection;
    }

    public BobaStrawTransaction command(String name, String... arguments) {
        if (finished) {
            throw new IllegalStateException("Transaction has already finished");
        }
        String[] command = new String[arguments.length + 1];
        command[0] = name;
        System.arraycopy(arguments, 0, command, 1, arguments.length);
        commands.add(command);
        return this;
    }

    public CompletionStage<RespValue> watch(String... keys) {
        ensureOpen();
        return client.executeOn(connection, "WATCH", keys);
    }

    public CompletionStage<RespValue> unwatch() {
        ensureOpen();
        return client.executeOn(connection, "UNWATCH");
    }

    public CompletionStage<List<RespValue>> exec() {
        ensureOpen();
        finished = true;
        CompletionStage<RespValue> chain = client.executeOn(connection, "MULTI");
        for (String[] command : commands) {
            chain = chain.thenCompose(ignored -> client.executeOn(connection,
                command[0], tail(command)
            ));
        }
        return chain.thenCompose(ignored -> client.executeOn(connection, "EXEC"))
            .thenApply(BobaStrawTransaction::arrayResult)
            .whenComplete((value, error) -> client.releaseTransaction(connection, error == null));
    }

    public CompletionStage<RespValue> discard() {
        ensureOpen();
        finished = true;
        return client.executeOn(connection, "DISCARD")
            .whenComplete((value, error) -> client.releaseTransaction(connection, error == null));
    }

    private void ensureOpen() {
        if (finished) {
            throw new IllegalStateException("Transaction has already finished");
        }
    }

    private static String[] tail(String[] command) {
        String[] arguments = new String[command.length - 1];
        System.arraycopy(command, 1, arguments, 0, arguments.length);
        return arguments;
    }

    private static List<RespValue> arrayResult(RespValue value) {
        if (value instanceof RespValue.Null) {
            return new ArrayList<RespValue>();
        }
        if (!(value instanceof RespValue.Array)) {
            throw new IllegalStateException("EXEC returned " + value.getClass().getSimpleName());
        }
        return ((RespValue.Array) value).values;
    }
}
