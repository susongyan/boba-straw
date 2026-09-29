package io.github.susongyan.bobastraw;

/** Cluster policy over the common command metadata. Unknown keys are never guessed. */
final class ClusterCommandRouting {
    private ClusterCommandRouting() {
    }

    static void validate(String command, String[] arguments) {
        CommandRegistry.requireOrdinary(command, CommandArgs.text(arguments));
    }

    static Integer slot(String command, String[] arguments) {
        CommandArgs args = CommandArgs.text(arguments);
        CommandRegistry.requireOrdinary(command, args);
        CommandSpec spec = CommandRegistry.resolve(command, args);
        if (spec != null) {
            return spec.slot(args);
        }
        if ("CLUSTER".equals(CommandArgs.commandName(command)) && args.size() > 0) {
            String subcommand = CommandArgs.commandName(args.control(0));
            if ("SLOTS".equals(subcommand) || "NODES".equals(subcommand)
                || "INFO".equals(subcommand) || "KEYSLOT".equals(subcommand)) {
                return null;
            }
        }
        throw new UnknownCommandException(
            "Unknown Cluster key metadata; use executeWithKeysAsync with all keys: " + command);
    }

    static Integer explicitSlot(String[] keys, String command, String[] arguments) {
        Integer declared = sameSlot(keys);
        try {
            Integer actual = slot(command, arguments);
            if (actual == null ? declared != null : !actual.equals(declared)) {
                throw new IllegalArgumentException("Explicit keys disagree with known command routing");
            }
        } catch (UnknownCommandException allowed) {
            // Unknown ordinary commands rely on the caller's complete key declaration.
        }
        return declared;
    }

    static Integer sameSlot(String[] keys) {
        return sameSlot(CommandArgs.text(keys));
    }

    static Integer sameSlot(CommandArgs args) {
        if (args.size() == 0) {
            return null;
        }
        return new CommandSpec("explicit keys", CommandSpec.Keys.ALL, CommandSpec.Connection.ORDINARY,
            CommandSpec.Access.UNKNOWN, null).slot(args);
    }

    private static final class UnknownCommandException extends IllegalArgumentException {
        private UnknownCommandException(String message) {
            super(message);
        }
    }
}
