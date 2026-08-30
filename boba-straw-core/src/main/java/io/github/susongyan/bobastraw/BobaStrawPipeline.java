package io.github.susongyan.bobastraw;

import io.github.susongyan.bobastraw.protocol.RespValue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Ordered command batch. Commands are written and matched in insertion order. */
public final class BobaStrawPipeline {
    private final BobaStrawClient client;
    private final List<String[]> commands = new ArrayList<String[]>();

    BobaStrawPipeline(BobaStrawClient client) {
        this.client = client;
    }

    public BobaStrawPipeline command(String name, String... arguments) {
        String[] command = new String[arguments.length + 1];
        command[0] = name;
        System.arraycopy(arguments, 0, command, 1, arguments.length);
        commands.add(command);
        return this;
    }

    public CompletionStage<List<RespValue>> execute() {
        CompletableFuture<List<RespValue>> result =
            CompletableFuture.completedFuture(new ArrayList<RespValue>());
        for (String[] command : commands) {
            result = result.thenCompose(values ->
                client.executeAsync(command[0], tail(command))
                    .thenApply(value -> {
                        values.add(value);
                        return values;
                    })
                    .toCompletableFuture()
            );
        }
        return result.thenApply(values -> Collections.unmodifiableList(values));
    }

    private static String[] tail(String[] command) {
        String[] arguments = new String[command.length - 1];
        System.arraycopy(command, 1, arguments, 0, arguments.length);
        return arguments;
    }
}
