package io.github.susongyan.bobastraw;

import io.github.susongyan.bobastraw.protocol.RespValue;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Shared Lua argument construction. No script cache, routing or retry logic lives here. */
final class ScriptCommandFactory {
    private ScriptCommandFactory() {
    }

    static <T> TypedCommand<T> text(boolean bySha, String script, ScriptOutput<T> output,
                                   String[] keys, String[] arguments) {
        require(script != null, "Script or SHA is required");
        require(output != null, "Script output is required");
        CommandArgs.text(keys);
        CommandArgs.text(arguments);
        String[] values = new String[2 + keys.length + arguments.length];
        values[0] = bySha ? sha(script) : script;
        values[1] = Integer.toString(keys.length);
        System.arraycopy(keys, 0, values, 2, keys.length);
        System.arraycopy(arguments, 0, values, 2 + keys.length, arguments.length);
        return new TypedCommand<T>(bySha ? "EVALSHA" : "EVAL", output::decode, values);
    }

    static <T> TypedCommand<T> binary(boolean bySha, byte[] script, ScriptOutput<T> output,
                                     byte[][] keys, byte[][] arguments) {
        require(script != null, "Script or SHA is required");
        require(output != null, "Script output is required");
        CommandArgs.binary(keys);
        CommandArgs.binary(arguments);
        byte[][] values = new byte[2 + keys.length + arguments.length][];
        values[0] = script;
        values[1] = Integer.toString(keys.length).getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(keys, 0, values, 2, keys.length);
        System.arraycopy(arguments, 0, values, 2 + keys.length, arguments.length);
        return TypedCommand.binary(bySha ? "EVALSHA" : "EVAL", output::decode, values);
    }

    static TypedCommand<String> load(String script) {
        require(script != null, "Script is required");
        return new TypedCommand<String>("SCRIPT", ScriptCommandFactory::loadedSha, "LOAD", script);
    }

    static TypedCommand<String> load(byte[] script) {
        require(script != null, "Script is required");
        return TypedCommand.binary("SCRIPT", ScriptCommandFactory::loadedSha,
            "LOAD".getBytes(StandardCharsets.US_ASCII), script);
    }

    static String sha(String value) {
        require(value != null && value.length() == 40, "Script SHA must contain 40 hexadecimal characters");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            require(c >= '0' && c <= '9' || c >= 'a' && c <= 'f' || c >= 'A' && c <= 'F',
                "Script SHA must contain ASCII hexadecimal characters");
        }
        return value.toLowerCase(Locale.ROOT);
    }

    private static String loadedSha(RespValue value) {
        String result = ScriptOutput.string().decode(value);
        try {
            return sha(result);
        } catch (IllegalArgumentException error) {
            throw new IllegalStateException("Invalid SCRIPT LOAD digest reply", error);
        }
    }

    private static void require(boolean valid, String message) {
        if (!valid) {
            throw new IllegalArgumentException(message);
        }
    }
}
