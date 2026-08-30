package io.github.susongyan.bobastraw.protocol;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Incremental, binary-safe RESP2/RESP3 codec. */
public final class RespCodec {
    private RespCodec() {
    }

    public static byte[] encodeCommand(String[] parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeAscii(out, "*" + parts.length + "\r\n");

        for (String part : parts) {
            byte[] bytes = part.getBytes(StandardCharsets.UTF_8);
            writeAscii(out, "$" + bytes.length + "\r\n");
            out.write(bytes, 0, bytes.length);
            writeAscii(out, "\r\n");
        }

        return out.toByteArray();
    }

    private static void writeAscii(ByteArrayOutputStream out, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
        out.write(bytes, 0, bytes.length);
    }

    public static final class Decoder {
        private byte[] buffer = new byte[0];

        public synchronized void feed(byte[] bytes, int length) {
            byte[] next = new byte[buffer.length + length];
            System.arraycopy(buffer, 0, next, 0, buffer.length);
            System.arraycopy(bytes, 0, next, buffer.length, length);
            buffer = next;
        }

        public synchronized RespValue poll() {
            ParseResult result = parse(buffer, 0);
            if (result == null) {
                return null;
            }

            byte[] next = new byte[buffer.length - result.next];
            System.arraycopy(buffer, result.next, next, 0, next.length);
            buffer = next;
            return result.value;
        }

        private ParseResult parse(byte[] source, int offset) {
            if (offset >= source.length) {
                return null;
            }

            switch (source[offset]) {
                case '+': return simple(source, offset, false);
                case '-': return simple(source, offset, true);
                case ':': return number(source, offset);
                case '$': return blob(source, offset);
                case '!': return blobError(source, offset);
                case '=': return verbatim(source, offset);
                case '(': return bigNumber(source, offset);
                case '*': return aggregate(source, offset, '*');
                case '%': return aggregate(source, offset, '%');
                case '~': return aggregate(source, offset, '~');
                case '>': return aggregate(source, offset, '>');
                case '|': return attribute(source, offset);
                case '_': return nullValue(source, offset);
                case '#': return bool(source, offset);
                case ',': return decimal(source, offset);
                default:
                    throw new IllegalArgumentException(
                        "Unsupported RESP marker: " + (char) source[offset]
                    );
            }
        }

        private ParseResult simple(byte[] source, int offset, boolean error) {
            int end = crlf(source, offset + 1);
            if (end < 0) {
                return null;
            }
            String value = new String(source, offset + 1, end - offset - 1, StandardCharsets.UTF_8);
            RespValue response = error ? new RespValue.Error(value) : new RespValue.SimpleString(value);
            return new ParseResult(response, end + 2);
        }

        private ParseResult number(byte[] source, int offset) {
            int end = crlf(source, offset + 1);
            if (end < 0) {
                return null;
            }
            return new ParseResult(
                new RespValue.Number(Long.parseLong(ascii(source, offset + 1, end))),
                end + 2
            );
        }

        private ParseResult decimal(byte[] source, int offset) {
            int end = crlf(source, offset + 1);
            if (end < 0) {
                return null;
            }
            return new ParseResult(
                new RespValue.DoubleValue(Double.parseDouble(ascii(source, offset + 1, end))),
                end + 2
            );
        }

        private ParseResult nullValue(byte[] source, int offset) {
            return source.length >= offset + 3
                ? new ParseResult(RespValue.Null.INSTANCE, offset + 3)
                : null;
        }

        private ParseResult bool(byte[] source, int offset) {
            if (source.length < offset + 4) {
                return null;
            }
            return new ParseResult(new RespValue.BooleanValue(source[offset + 1] == 't'), offset + 4);
        }

        private ParseResult blob(byte[] source, int offset) {
            int end = crlf(source, offset + 1);
            if (end < 0) {
                return null;
            }
            int length = Integer.parseInt(ascii(source, offset + 1, end));
            if (length == -1) {
                return new ParseResult(RespValue.Null.INSTANCE, end + 2);
            }

            int data = end + 2;
            if (source.length < data + length + 2) {
                return null;
            }

            byte[] value = new byte[length];
            System.arraycopy(source, data, value, 0, length);
            return new ParseResult(new RespValue.BlobString(value), data + length + 2);
        }

        private ParseResult blobError(byte[] source, int offset) {
            ParseResult value = blobBytes(source, offset);
            if (value == null) {
                return null;
            }
            return new ParseResult(new RespValue.BlobError(((RespValue.BlobString) value.value).value), value.next);
        }

        private ParseResult verbatim(byte[] source, int offset) {
            ParseResult value = blobBytes(source, offset);
            if (value == null) {
                return null;
            }
            byte[] bytes = ((RespValue.BlobString) value.value).value;
            int separator = -1;
            for (int index = 0; index < bytes.length; index++) {
                if (bytes[index] == ':') {
                    separator = index;
                    break;
                }
            }
            if (separator <= 0) {
                throw new IllegalArgumentException("Invalid RESP verbatim string");
            }
            return new ParseResult(
                new RespValue.VerbatimString(
                    new String(bytes, 0, separator, StandardCharsets.US_ASCII),
                    copyOfRange(bytes, separator + 1, bytes.length)
                ),
                value.next
            );
        }

        private ParseResult bigNumber(byte[] source, int offset) {
            int end = crlf(source, offset + 1);
            if (end < 0) {
                return null;
            }
            return new ParseResult(
                new RespValue.BigNumber(ascii(source, offset + 1, end)),
                end + 2
            );
        }

        private ParseResult blobBytes(byte[] source, int offset) {
            int end = crlf(source, offset + 1);
            if (end < 0) {
                return null;
            }
            int length = Integer.parseInt(ascii(source, offset + 1, end));
            if (length < 0) {
                throw new IllegalArgumentException("Null blob is not valid for this RESP type");
            }
            int data = end + 2;
            if (source.length < data + length + 2) {
                return null;
            }
            byte[] value = new byte[length];
            System.arraycopy(source, data, value, 0, length);
            return new ParseResult(new RespValue.BlobString(value), data + length + 2);
        }

        private byte[] copyOfRange(byte[] source, int start, int end) {
            byte[] result = new byte[end - start];
            System.arraycopy(source, start, result, 0, result.length);
            return result;
        }

        private ParseResult aggregate(byte[] source, int offset, char type) {
            int end = crlf(source, offset + 1);
            if (end < 0) {
                return null;
            }

            int count = Integer.parseInt(ascii(source, offset + 1, end));
            if (count == -1) {
                return new ParseResult(RespValue.Null.INSTANCE, end + 2);
            }

            int items = type == '%' ? count * 2 : count;
            List<RespValue> values = new ArrayList<RespValue>(items);
            int cursor = end + 2;
            for (int index = 0; index < items; index++) {
                ParseResult nested = parse(source, cursor);
                if (nested == null) {
                    return null;
                }
                values.add(nested.value);
                cursor = nested.next;
            }

            RespValue value = aggregateValue(type, values);
            return new ParseResult(value, cursor);
        }

        private RespValue aggregateValue(char type, List<RespValue> values) {
            if (type == '%') {
                Map<RespValue, RespValue> map = new LinkedHashMap<RespValue, RespValue>();
                for (int index = 0; index < values.size(); index += 2) {
                    map.put(values.get(index), values.get(index + 1));
                }
                return new RespValue.MapValue(map);
            }
            if (type == '~') {
                return new RespValue.SetValue(values);
            }
            if (type == '>') {
                return new RespValue.Push(values);
            }
            return new RespValue.Array(values);
        }

        private ParseResult attribute(byte[] source, int offset) {
            ParseResult attributes = aggregate(source, offset, '%');
            if (attributes == null) {
                return null;
            }
            if (!(attributes.value instanceof RespValue.MapValue)) {
                throw new IllegalArgumentException("RESP attribute is not a map");
            }

            ParseResult value = parse(source, attributes.next);
            if (value == null) {
                return null;
            }
            return new ParseResult(
                new RespValue.Attribute((RespValue.MapValue) attributes.value, value.value),
                value.next
            );
        }

        private int crlf(byte[] source, int start) {
            for (int index = start; index + 1 < source.length; index++) {
                if (source[index] == '\r' && source[index + 1] == '\n') {
                    return index;
                }
            }
            return -1;
        }

        private String ascii(byte[] source, int start, int end) {
            return new String(source, start, end - start, StandardCharsets.US_ASCII);
        }

        private static final class ParseResult {
            private final RespValue value;
            private final int next;

            private ParseResult(RespValue value, int next) {
                this.value = value;
                this.next = next;
            }
        }
    }
}
