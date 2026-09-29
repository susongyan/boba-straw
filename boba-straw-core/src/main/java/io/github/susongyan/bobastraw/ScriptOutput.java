package io.github.susongyan.bobastraw;

import io.github.susongyan.bobastraw.protocol.RespValue;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Immutable, explicit Lua reply contract. It does not alter the script or retry execution.
 * Null replies remain null; an unexpected reply type fails result decoding.
 * Use {@link #bytes()} for arbitrary binary payloads and {@link #raw()} for mixed aggregates.
 */
public final class ScriptOutput<T> {
    private final String kind;
    private final ScriptOutput<?> element;
    private final CommandDecoder<T> decoder;

    private ScriptOutput(String kind, ScriptOutput<?> element, CommandDecoder<T> decoder) {
        this.kind = kind;
        this.element = element;
        this.decoder = decoder;
    }

    /** Preserves RESP structure, including nested errors. Top-level errors still fail. */
    public static ScriptOutput<RespValue> raw() {
        return new ScriptOutput<RespValue>("raw", null, value -> value);
    }

    /** Accepts only RESP Integer or Null, without numeric coercion. */
    public static ScriptOutput<Long> integer() {
        return new ScriptOutput<Long>("integer", null, value -> {
            if (isNull(value)) {
                return null;
            }
            if (!(value instanceof RespValue.Number)) {
                throw mismatch("integer", value);
            }
            return value.asLong();
        });
    }

    /** Decodes string replies as UTF-8; it is not suitable for arbitrary binary data. */
    public static ScriptOutput<String> string() {
        return new ScriptOutput<String>("string", null, value -> {
            byte[] bytes = stringBytes(value);
            return bytes == null ? null : new String(bytes, StandardCharsets.UTF_8);
        });
    }

    /** Returns a defensive copy for string replies, or null. */
    public static ScriptOutput<byte[]> bytes() {
        return new ScriptOutput<byte[]>("bytes", null, value -> {
            byte[] bytes = stringBytes(value);
            return bytes == null ? null : bytes.clone();
        });
    }

    /** Accepts arrays only; preserves order, empty arrays and null elements. */
    public static <E> ScriptOutput<List<E>> list(ScriptOutput<E> element) {
        if (element == null) {
            throw new IllegalArgumentException("Script list element output is required");
        }
        return new ScriptOutput<List<E>>("list", element, value -> {
            if (isNull(value)) {
                return null;
            }
            if (!(value instanceof RespValue.Array)) {
                throw mismatch("array", value);
            }
            List<E> values = new ArrayList<E>();
            for (RespValue item : ((RespValue.Array) value).values) {
                values.add(element.decode(item));
            }
            return Collections.unmodifiableList(values);
        });
    }

    T decode(RespValue value) {
        while (value instanceof RespValue.Attribute) {
            value = ((RespValue.Attribute) value).value;
        }
        if (value == null) {
            throw new IllegalStateException("Missing script reply");
        }
        if (value instanceof RespValue.Error) {
            throw new BobaStrawServerException(((RespValue.Error) value).message);
        }
        if (value instanceof RespValue.BlobError) {
            throw new BobaStrawServerException(((RespValue.BlobError) value).message());
        }
        return decoder.apply(value);
    }

    private static boolean isNull(RespValue value) {
        return value instanceof RespValue.Null
            || value instanceof RespValue.BlobString && ((RespValue.BlobString) value).value == null;
    }

    private static byte[] stringBytes(RespValue value) {
        if (isNull(value)) {
            return null;
        }
        if (value instanceof RespValue.BlobString) {
            return ((RespValue.BlobString) value).value;
        }
        if (value instanceof RespValue.SimpleString) {
            return value.asString().getBytes(StandardCharsets.UTF_8);
        }
        if (value instanceof RespValue.VerbatimString) {
            return ((RespValue.VerbatimString) value).value;
        }
        throw mismatch("string", value);
    }

    private static IllegalStateException mismatch(String expected, RespValue value) {
        return new IllegalStateException("Expected script " + expected + " reply, got "
            + value.getClass().getSimpleName());
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof ScriptOutput)) {
            return false;
        }
        ScriptOutput<?> output = (ScriptOutput<?>) other;
        return kind.equals(output.kind) && Objects.equals(element, output.element);
    }

    @Override
    public int hashCode() {
        return Objects.hash(kind, element);
    }

    @Override
    public String toString() {
        return element == null ? kind : kind + "<" + element + ">";
    }
}
