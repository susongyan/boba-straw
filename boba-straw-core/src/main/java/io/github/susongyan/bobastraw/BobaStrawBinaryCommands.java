package io.github.susongyan.bobastraw;

import io.github.susongyan.bobastraw.protocol.RespValue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionStage;

/** Binary-safe command facade. Values are never decoded through the default charset. */
public final class BobaStrawBinaryCommands {
    private final BobaStrawClient client;

    BobaStrawBinaryCommands(BobaStrawClient client) {
        this.client = client;
    }

    public CompletionStage<byte[]> get(byte[] key) {
        return BobaStrawStages.map(
            client.executeBinaryAsync("GET".getBytes(java.nio.charset.StandardCharsets.US_ASCII), key),
            BobaStrawBinaryCommands::bytes
        );
    }

    public CompletionStage<byte[]> set(byte[] key, byte[] value) {
        return BobaStrawStages.map(
            client.executeBinaryAsync("SET".getBytes(java.nio.charset.StandardCharsets.US_ASCII), key, value),
            BobaStrawBinaryCommands::bytes
        );
    }

    /**
     * Uses the same options as the String API. Returns OK, null, or the old bytes
     * when GET is requested. GET/EXAT/PXAT require Redis 6.2+, KEEPTTL 6.0+,
     * and NX with GET 7.0+. Unsupported options remain server errors.
     */
    public CompletionStage<byte[]> set(byte[] key, byte[] value, SetArgs options) {
        if (options == null) {
            throw new IllegalArgumentException("SET options must not be null");
        }
        String[] suffix = options.arguments();
        byte[][] arguments = new byte[suffix.length + 2][];
        arguments[0] = key;
        arguments[1] = value;
        for (int index = 0; index < suffix.length; index++) {
            arguments[index + 2] = ascii(suffix[index]);
        }
        return BobaStrawStages.map(execute("SET", arguments), BobaStrawBinaryCommands::bytes);
    }

    /** Returns one entry per key, preserving order and duplicates; missing/non-string values are null. */
    public CompletionStage<List<byte[]>> mget(byte[]... keys) {
        return BobaStrawStages.map(execute("MGET", keys), BobaStrawBinaryCommands::byteList);
    }

    /** Alternating key/value pairs, not a Map whose byte[] keys would use identity equality. */
    public CompletionStage<byte[]> mset(byte[]... keyValues) {
        requirePairs(keyValues);
        return BobaStrawStages.map(execute("MSET", keyValues), BobaStrawBinaryCommands::bytes);
    }

    /** Atomically writes all pairs only if all keys are absent; returns false without partial writes. */
    public CompletionStage<Boolean> msetNx(byte[]... keyValues) {
        requirePairs(keyValues);
        return BobaStrawStages.map(execute("MSETNX", keyValues), value -> value.asLong() != 0);
    }

    public CompletionStage<Long> append(byte[] key, byte[] value) {
        return BobaStrawStages.map(execute("APPEND", key, value), RespValue::asLong);
    }

    public CompletionStage<Long> strlen(byte[] key) {
        return BobaStrawStages.map(execute("STRLEN", key), RespValue::asLong);
    }

    /** Inclusive byte offsets; negative offsets count from the end. Missing ranges return empty bytes. */
    public CompletionStage<byte[]> getRange(byte[] key, long start, long end) {
        return BobaStrawStages.map(
            execute("GETRANGE", key, ascii(Long.toString(start)), ascii(Long.toString(end))),
            BobaStrawBinaryCommands::bytes
        );
    }

    /** Overwrites at a byte offset, padding with zero bytes when necessary. */
    public CompletionStage<Long> setRange(byte[] key, long offset, byte[] value) {
        if (offset < 0) {
            throw new IllegalArgumentException("SETRANGE offset must not be negative");
        }
        return BobaStrawStages.map(
            execute("SETRANGE", key, ascii(Long.toString(offset)), value), RespValue::asLong
        );
    }

    private CompletionStage<RespValue> execute(String command, byte[]... arguments) {
        if (arguments == null || arguments.length == 0) {
            throw new IllegalArgumentException(command + " requires arguments");
        }
        for (byte[] argument : arguments) {
            if (argument == null) {
                throw new IllegalArgumentException(command + " arguments must not be null");
            }
        }
        return client.executeBinaryAsync(ascii(command), arguments);
    }

    private static void requirePairs(byte[][] keyValues) {
        if (keyValues == null || keyValues.length == 0 || keyValues.length % 2 != 0) {
            throw new IllegalArgumentException("At least one complete key/value pair is required");
        }
    }

    private static byte[] ascii(String value) {
        return value.getBytes(StandardCharsets.US_ASCII);
    }

    private static List<byte[]> byteList(RespValue value) {
        if (!(value instanceof RespValue.Array)) {
            throw new IllegalStateException("Expected an array reply for MGET");
        }
        List<byte[]> result = new ArrayList<byte[]>();
        for (RespValue item : ((RespValue.Array) value).values) {
            result.add(bytes(item));
        }
        return result;
    }

    public CompletionStage<Long> del(byte[]... keys) {
        byte[][] arguments = new byte[keys.length + 1][];
        arguments[0] = "DEL".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        System.arraycopy(keys, 0, arguments, 1, keys.length);
        return BobaStrawStages.map(
            client.executeBinaryAsync(arguments[0], tail(arguments)),
            RespValue::asLong
        );
    }

    private static byte[][] tail(byte[][] values) {
        byte[][] result = new byte[values.length - 1][];
        System.arraycopy(values, 1, result, 0, result.length);
        return result;
    }

    private static byte[] bytes(RespValue value) {
        if (value instanceof RespValue.Null) {
            return null;
        }
        if (value instanceof RespValue.BlobString) {
            return ((RespValue.BlobString) value).value;
        }
        return value.asString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }
}
