package io.github.susongyan.bobastraw;

import io.github.susongyan.bobastraw.protocol.RespValue;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletionStage;

/** Binary transport adapter; only command names, never keys or values, are encoded as text. */
@FunctionalInterface
interface BinaryCommandExecutor {
    CompletionStage<RespValue> executeAsync(byte[] command, byte[]... arguments);

    default <T> CompletionStage<T> execute(TypedCommand<T> command) {
        return BobaStrawStages.map(
            executeAsync(command.name().getBytes(StandardCharsets.US_ASCII), command.binaryArguments()),
            command.decoder()
        );
    }
}
