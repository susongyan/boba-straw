package io.github.susongyan.bobastraw;

import io.github.susongyan.bobastraw.protocol.RespValue;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.AbstractMap;
import java.nio.charset.StandardCharsets;

/** Shared response contracts for the high-frequency typed facades. */
final class CommandDecoders {
    static final CommandDecoder<Long> LONG = RespValue::asLong;
    static final CommandDecoder<Boolean> BOOLEAN = value -> value.asLong() != 0;
    static final CommandDecoder<String> STRING = RespValue::asString;
    static final CommandDecoder<Long> NULLABLE_LONG = value ->
        value instanceof RespValue.Null ? null : value.asLong();
    static final CommandDecoder<Double> NULLABLE_DOUBLE = value -> {
        if (value instanceof RespValue.Null) {
            return null;
        }
        return value instanceof RespValue.DoubleValue ? ((RespValue.DoubleValue) value).value
            : Double.parseDouble(value.asString());
    };
    static final CommandDecoder<byte[]> BYTES = value -> {
        if (value instanceof RespValue.Null) {
            return null;
        }
        if (value instanceof RespValue.BlobString) {
            return ((RespValue.BlobString) value).value;
        }
        if (value instanceof RespValue.SimpleString) {
            return value.asString().getBytes(StandardCharsets.UTF_8);
        }
        throw new IllegalStateException("Expected string or null reply");
    };
    static final CommandDecoder<List<byte[]>> BYTE_LIST = value -> {
        List<byte[]> result = new ArrayList<byte[]>();
        for (RespValue item : elements(value)) {
            result.add(BYTES.apply(item));
        }
        return result;
    };
    static final CommandDecoder<List<Map.Entry<byte[], byte[]>>> BYTE_ENTRIES = value -> {
        List<Map.Entry<byte[], byte[]>> result = new ArrayList<Map.Entry<byte[], byte[]>>();
        if (value instanceof RespValue.MapValue) {
            for (Map.Entry<RespValue, RespValue> entry : ((RespValue.MapValue) value).values.entrySet()) {
                result.add(entry(entry.getKey(), entry.getValue()));
            }
        } else {
            List<RespValue> items = elements(value);
            if (items.size() % 2 != 0) {
                throw new IllegalStateException("Expected field/value pairs");
            }
            for (int index = 0; index < items.size(); index += 2) {
                result.add(entry(items.get(index), items.get(index + 1)));
            }
        }
        return result;
    };

    private CommandDecoders() {
    }

    private static List<RespValue> elements(RespValue value) {
        if (value instanceof RespValue.Array) {
            return ((RespValue.Array) value).values;
        }
        if (value instanceof RespValue.SetValue) {
            return ((RespValue.SetValue) value).values;
        }
        throw new IllegalStateException("Expected aggregate reply");
    }

    private static Map.Entry<byte[], byte[]> entry(RespValue key, RespValue value) {
        return new AbstractMap.SimpleImmutableEntry<byte[], byte[]>(BYTES.apply(key), BYTES.apply(value));
    }
}
