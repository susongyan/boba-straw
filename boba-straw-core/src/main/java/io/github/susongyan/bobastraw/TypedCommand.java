package io.github.susongyan.bobastraw;

/** Immutable ordinary text invocation. Not a public extension SPI or a retry instruction. */
final class TypedCommand<T> {
    private final String name;
    private final String[] arguments;
    private final CommandDecoder<T> decoder;

    TypedCommand(String name, CommandDecoder<T> decoder, String... arguments) {
        this.name = CommandArgs.commandName(name);
        CommandRegistry.requireOrdinary(this.name, CommandArgs.text(arguments));
        if (decoder == null) {
            throw new IllegalArgumentException("Command decoder is required");
        }
        this.arguments = arguments.clone();
        this.decoder = decoder;
    }

    String name() {
        return name;
    }

    String[] arguments() {
        return arguments.clone();
    }

    CommandDecoder<T> decoder() {
        return decoder;
    }
}
