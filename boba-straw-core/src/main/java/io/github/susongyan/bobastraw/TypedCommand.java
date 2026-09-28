package io.github.susongyan.bobastraw;

/** Immutable ordinary text or binary invocation. Not a public extension SPI or a retry instruction. */
final class TypedCommand<T> {
    private final String name;
    private final String[] arguments;
    private final byte[][] binaryArguments;
    private final CommandDecoder<T> decoder;

    TypedCommand(String name, CommandDecoder<T> decoder, String... arguments) {
        this.name = CommandArgs.commandName(name);
        CommandRegistry.requireOrdinary(this.name, CommandArgs.text(arguments));
        if (decoder == null) {
            throw new IllegalArgumentException("Command decoder is required");
        }
        this.arguments = arguments.clone();
        this.binaryArguments = null;
        this.decoder = decoder;
    }

    private TypedCommand(String name, byte[][] arguments, CommandDecoder<T> decoder) {
        this.name = CommandArgs.commandName(name);
        CommandRegistry.requireOrdinary(this.name, CommandArgs.binary(arguments));
        if (decoder == null) {
            throw new IllegalArgumentException("Command decoder is required");
        }
        this.arguments = null;
        this.binaryArguments = copy(arguments);
        this.decoder = decoder;
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

    byte[][] binaryArguments() {
        if (binaryArguments == null) {
            throw new IllegalStateException("Text command requires a text executor");
        }
        return copy(binaryArguments);
    }

    private static byte[][] copy(byte[][] values) {
        byte[][] result = new byte[values.length][];
        for (int index = 0; index < values.length; index++) {
            result[index] = values[index].clone();
        }
        return result;
    }

    CommandDecoder<T> decoder() {
        return decoder;
    }
}
