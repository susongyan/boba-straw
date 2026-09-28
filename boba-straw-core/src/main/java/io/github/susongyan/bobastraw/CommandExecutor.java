package io.github.susongyan.bobastraw;

import io.github.susongyan.bobastraw.protocol.RespValue;
import java.util.concurrent.CompletionStage;

/** Topology-neutral ordinary execution; routing and connection ownership stay with the client. */
@FunctionalInterface
interface CommandExecutor {
    CompletionStage<RespValue> executeAsync(String command, String... arguments);

    default <T> CompletionStage<T> execute(TypedCommand<T> command) {
        return BobaStrawStages.map(executeAsync(command.name(), command.arguments()), command.decoder());
    }
}
