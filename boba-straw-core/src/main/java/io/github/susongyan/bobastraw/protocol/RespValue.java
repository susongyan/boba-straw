package io.github.susongyan.bobastraw.protocol;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Complete RESP value model. RESP2 values are a subset of these types. */
public abstract class RespValue {
    public String asString() {
        throw new IllegalStateException("Not a string reply: " + getClass().getSimpleName());
    }

    public long asLong() {
        throw new IllegalStateException("Not a number reply: " + getClass().getSimpleName());
    }

    public static final class SimpleString extends RespValue {
        public final String value;

        public SimpleString(String value) {
            this.value = value;
        }

        @Override
        public String asString() {
            return value;
        }
    }

    public static final class BlobString extends RespValue {
        public final byte[] value;

        public BlobString(byte[] value) {
            this.value = value;
        }

        @Override
        public String asString() {
            return value == null ? null : new String(value, StandardCharsets.UTF_8);
        }
    }

    public static final class Number extends RespValue {
        public final long value;

        public Number(long value) {
            this.value = value;
        }

        @Override
        public long asLong() {
            return value;
        }
    }

    public static final class Null extends RespValue {
        public static final Null INSTANCE = new Null();

        private Null() {
        }

        @Override
        public String asString() {
            return null;
        }
    }

    public static final class Error extends RespValue {
        public final String message;

        public Error(String message) {
            this.message = message;
        }
    }

    public static final class BlobError extends RespValue {
        public final byte[] value;

        public BlobError(byte[] value) {
            this.value = value;
        }

        public String message() {
            return new String(value, StandardCharsets.UTF_8);
        }
    }

    public static final class VerbatimString extends RespValue {
        public final String format;
        public final byte[] value;

        public VerbatimString(String format, byte[] value) {
            this.format = format;
            this.value = value;
        }

        @Override
        public String asString() {
            return new String(value, StandardCharsets.UTF_8);
        }
    }

    public static final class BigNumber extends RespValue {
        public final String value;

        public BigNumber(String value) {
            this.value = value;
        }

        @Override
        public String asString() {
            return value;
        }
    }

    public static final class Array extends RespValue {
        public final List<RespValue> values;

        public Array(List<RespValue> values) {
            this.values = Collections.unmodifiableList(values);
        }
    }

    public static final class MapValue extends RespValue {
        public final Map<RespValue, RespValue> values;

        public MapValue(Map<RespValue, RespValue> values) {
            this.values = Collections.unmodifiableMap(values);
        }
    }

    public static final class SetValue extends RespValue {
        public final List<RespValue> values;

        public SetValue(List<RespValue> values) {
            this.values = Collections.unmodifiableList(values);
        }
    }

    public static final class BooleanValue extends RespValue {
        public final boolean value;

        public BooleanValue(boolean value) {
            this.value = value;
        }
    }

    public static final class DoubleValue extends RespValue {
        public final double value;

        public DoubleValue(double value) {
            this.value = value;
        }
    }

    public static final class Attribute extends RespValue {
        public final MapValue attributes;
        public final RespValue value;

        public Attribute(MapValue attributes, RespValue value) {
            this.attributes = attributes;
            this.value = value;
        }
    }

    public static final class Push extends RespValue {
        public final List<RespValue> values;

        public Push(List<RespValue> values) {
            this.values = Collections.unmodifiableList(values);
        }
    }
}
