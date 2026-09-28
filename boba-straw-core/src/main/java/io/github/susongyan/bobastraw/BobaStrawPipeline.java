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
    private final Object resultOwner = new Object();

    BobaStrawPipeline(BobaStrawClient client) {
        this.client = client;
    }

    public synchronized BobaStrawPipeline command(String name, String... arguments) {
        if (executed.get()) {
            throw new IllegalStateException("Pipeline has already been executed");
        }
        CommandRegistry.requireOrdinary(name, CommandArgs.text(arguments));
        String[] command = new String[arguments.length + 1];
        command[0] = name;
        System.arraycopy(arguments, 0, command, 1, arguments.length);
        commands.add(command);
        return this;
    }

    public BobaStrawBatchCommands typed() {
        return new BobaStrawBatchCommands(this::enqueue);
    }

    private synchronized <T> BobaStrawCommandHandle<T> enqueue(TypedCommand<T> command) {
        int index = commands.size();
        command(command.name(), command.arguments());
        return new BobaStrawCommandHandle<T>(resultOwner, index, command.decoder());
    }

    /** Runs once, retaining individual server errors for result.get(handle). */
    public CompletionStage<BobaStrawBatchResult> executeTyped() {
        List<String[]> snapshot = takeCommands();
        return BobaStrawStages.map(client.executeBatch(snapshot, true),
            values -> new BobaStrawBatchResult(resultOwner, values, snapshot.size(), false));
    }

    private synchronized List<String[]> takeCommands() {
        if (!executed.compareAndSet(false, true)) {
            throw new IllegalStateException("Pipeline has already been executed");
        }
        return new ArrayList<String[]>(commands);
    }

    public CompletionStage<List<RespValue>> execute() {
        CompletionStage<List<RespValue>> operation = client.executeBatch(takeCommands());
        CompletableFuture<List<RespValue>> result = new CompletableFuture<List<RespValue>>();
        operation.whenComplete((values, error) -> {
            if (error != null) {
                result.completeExceptionally(error);
            } else {
                result.complete(Collections.unmodifiableList(new ArrayList<RespValue>(values)));
            }
        });
        result.whenComplete((values, error) -> {
            if (result.isCancelled()) {
                operation.toCompletableFuture().cancel(false);
            }
        });
        return result;
    }

}
