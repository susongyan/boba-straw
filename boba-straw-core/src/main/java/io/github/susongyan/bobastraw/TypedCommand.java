package io.github.susongyan.bobastraw;

import io.github.susongyan.bobastraw.internal.EncodedCommand;
import java.nio.charset.StandardCharsets;

/** Immutable ordinary text or binary invocation. Not a public extension SPI or a retry instruction. */
final class TypedCommand<T> {
    private final String name;
    private final String[] arguments;
    private final EncodedCommand binaryFrame;
    private final CommandDecoder<T> decoder;
    private final Integer binarySlot;
    private final String binaryRoutingError;

    TypedCommand(String name, CommandDecoder<T> decoder, String... arguments) {
        this.name = CommandArgs.commandName(name);
        CommandRegistry.requireOrdinary(this.name, CommandArgs.text(arguments));
        if (decoder == null) {
            throw new IllegalArgumentException("Command decoder is required");
        }
        this.arguments = arguments.clone();
        this.binaryFrame = null;
        this.binarySlot = null;
        this.binaryRoutingError = null;
        this.decoder = decoder;
    }

    private TypedCommand(String name, byte[][] arguments, CommandDecoder<T> decoder) {
        this.name = CommandArgs.commandName(name);
        CommandRegistry.requireOrdinary(this.name, CommandArgs.binary(arguments));
        if (decoder == null) {
            throw new IllegalArgumentException("Command decoder is required");
        }
        this.arguments = null;
        byte[][] all = new byte[arguments.length + 1][];
        all[0] = this.name.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(arguments, 0, all, 1, arguments.length);
        this.binaryFrame = new EncodedCommand(all);
        this.decoder = decoder;
        Integer slot = null;
        String routingError = null;
        try {
            CommandSpec spec = CommandRegistry.resolve(this.name, CommandArgs.binary(arguments));
            if (spec == null) {
                routingError = "Unknown Cluster key metadata: " + this.name;
            } else {
                slot = spec.slot(CommandArgs.binary(arguments));
            }
        } catch (IllegalArgumentException error) {
            // Standalone/Sentinel allow cross-slot calls; reject only in the Cluster adapter.
            routingError = error.getMessage();
        }
        this.binarySlot = slot;
        this.binaryRoutingError = routingError;
    }

    static <T> TypedCommand<T> binary(String name, CommandDecoder<T> decoder, byte[]... arguments) {
        return new TypedCommand<T>(name, arguments, decoder);
    }

    String name() {
        return name;
    }

    String[] arguments() {
        if (arguments == null) {
            throw new IllegalStateException("Binary command requires a binary executor");
        }
        return arguments.clone();
    }

    EncodedCommand binaryFrame() {
        if (binaryFrame == null) {
            throw new IllegalStateException("Text command requires a text executor");
        }
        return binaryFrame;
    }

    CommandDecoder<T> decoder() {
        return decoder;
    }

    Integer binaryClusterSlot() {
        binaryFrame();
        if (binaryRoutingError != null) {
            throw new IllegalArgumentException(binaryRoutingError);
        }
        return binarySlot;
    }
}
