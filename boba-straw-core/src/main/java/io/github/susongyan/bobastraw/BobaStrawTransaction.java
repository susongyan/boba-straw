package io.github.susongyan.bobastraw;

import io.github.susongyan.bobastraw.protocol.RespValue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** MULTI/EXEC helper. A dedicated connection will be used when pooling is added. */
public final class BobaStrawTransaction {
    private final BobaStrawClient client;
    private final List<String[]> commands = new ArrayList<String[]>();

    BobaStrawTransaction(BobaStrawClient client) {
        this.client = client;
    }

    public BobaStrawTransaction command(String name, String... arguments) {
        String[] command = new String[arguments.length + 1];
        command[0] = name;
        System.arraycopy(arguments, 0, command, 1, arguments.length);
        commands.add(command);
        return this;
    }

    public CompletionStage<List<RespValue>> exec() {
        CompletionStage<RespValue> chain = client.executeAsync("MULTI");
        for (String[] command : commands) {
            chain = chain.thenCompose(ignored -> client.executeAsync(
                command[0], tail(command)
            ));
        }
        return chain.thenCompose(ignored -> client.executeAsync("EXEC"))
            .thenApply(BobaStrawTransaction::arrayResult);
    }

    public CompletionStage<RespValue> discard() {
        return client.executeAsync("DISCARD");
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
