package io.github.susongyan.bobastraw;

import io.github.susongyan.bobastraw.protocol.RespValue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;

/** Ordered command batch. Commands are written and matched in insertion order. */
public final class BobaStrawPipeline {
    private final BobaStrawClient client;
    private final List<String[]> commands = new ArrayList<String[]>();
    private final AtomicBoolean executed = new AtomicBoolean();

    BobaStrawPipeline(BobaStrawClient client) {
        this.client = client;
    }

    public BobaStrawPipeline command(String name, String... arguments) {
        if (executed.get()) {
            throw new IllegalStateException("Pipeline has already been executed");
        }
        String[] command = new String[arguments.length + 1];
        command[0] = name;
        System.arraycopy(arguments, 0, command, 1, arguments.length);
        commands.add(command);
        return this;
    }

    public CompletionStage<List<RespValue>> execute() {
        if (!executed.compareAndSet(false, true)) {
            throw new IllegalStateException("Pipeline has already been executed");
        }
        return client.executeBatch(commands)
            .thenApply(values -> Collections.unmodifiableList(new ArrayList<RespValue>(values)));
    }

}
