package io.github.susongyan.bobastraw;

import io.github.susongyan.bobastraw.protocol.RespValue;

import java.util.concurrent.CompletionStage;

/** Binary-safe command facade. Values are never decoded through the default charset. */
public final class BobaStrawBinaryCommands {
    private final BobaStrawClient client;

    BobaStrawBinaryCommands(BobaStrawClient client) {
        this.client = client;
    }

    public CompletionStage<byte[]> get(byte[] key) {
        return client.executeBinaryAsync("GET".getBytes(java.nio.charset.StandardCharsets.US_ASCII), key)
            .thenApply(BobaStrawBinaryCommands::bytes);
    }

    public CompletionStage<byte[]> set(byte[] key, byte[] value) {
        return client.executeBinaryAsync("SET".getBytes(java.nio.charset.StandardCharsets.US_ASCII), key, value)
            .thenApply(BobaStrawBinaryCommands::bytes);
    }

    public CompletionStage<Long> del(byte[]... keys) {
        byte[][] arguments = new byte[keys.length + 1][];
        arguments[0] = "DEL".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        System.arraycopy(keys, 0, arguments, 1, keys.length);
        return client.executeBinaryAsync(arguments[0], tail(arguments))
            .thenApply(RespValue::asLong);
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
