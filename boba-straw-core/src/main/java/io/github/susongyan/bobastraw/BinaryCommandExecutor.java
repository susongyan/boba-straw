package io.github.susongyan.bobastraw;

import io.github.susongyan.bobastraw.protocol.RespValue;
import io.github.susongyan.bobastraw.internal.EncodedCommand;
import java.util.concurrent.CompletionStage;

/** Binary transport adapter; only command names, never keys or values, are encoded as text. */
@FunctionalInterface
interface BinaryCommandExecutor {
    CompletionStage<RespValue> executeAsync(EncodedCommand command);

    default CompletionStage<RespValue> executeCommand(TypedCommand<?> command) {
        return executeAsync(command.binaryFrame());
    }

    default <T> CompletionStage<T> execute(TypedCommand<T> command) {
        return BobaStrawStages.map(
            executeCommand(command),
            command.decoder()
        );
    }
}
