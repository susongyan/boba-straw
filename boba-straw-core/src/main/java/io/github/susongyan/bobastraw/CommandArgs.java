package io.github.susongyan.bobastraw;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Short-lived read-only argument view; consumed before the transport encodes its own frame. */
final class CommandArgs {
    private final String[] text;
    private final byte[][] binary;

    private CommandArgs(String[] text, byte[][] binary) {
        this.text = text;
        this.binary = binary;
    }

    static CommandArgs text(String... values) {
        if (values == null) {
            throw new IllegalArgumentException("Arguments must not be null");
        }
        for (String value : values) {
            if (value == null) {
                throw new IllegalArgumentException("Arguments must not contain null");
            }
        }
        return new CommandArgs(values, null);
    }

    static CommandArgs binary(byte[]... values) {
        if (values == null) {
            throw new IllegalArgumentException("Arguments must not be null");
        }
        for (byte[] value : values) {
            if (value == null) {
                throw new IllegalArgumentException("Arguments must not contain null");
            }
        }
        return new CommandArgs(null, values);
    }

    int size() {
        return text == null ? binary.length : text.length;
    }

    String control(int index) {
        return text == null ? new String(binary[index], StandardCharsets.US_ASCII) : text[index];
    }

    int slot(int index) {
        return text == null ? ClusterSlot.ofBytes(binary[index]) : ClusterSlot.of(text[index]);
    }

    static String commandName(String value) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException("Command name is required");
        }
        for (int index = 0; index < value.length(); index++) {
            if (value.charAt(index) <= 32 || value.charAt(index) >= 127) {
                throw new IllegalArgumentException("Command name must be a single ASCII token");
            }
        }
        return value.toUpperCase(Locale.ROOT);
    }

    static String commandName(byte[] value) {
        if (value == null) {
            throw new IllegalArgumentException("Command name is required");
        }
        for (byte item : value) {
            if (item <= 32 || item >= 127) {
                throw new IllegalArgumentException("Command name must be a single ASCII token");
            }
        }
        return commandName(new String(value, StandardCharsets.US_ASCII));
    }
}
