package io.github.susongyan.bobastraw.protocol;

import io.github.susongyan.bobastraw.BobaStrawProtocolException;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Incremental, binary-safe RESP2/RESP3 codec. */
public final class RespCodec {
    private RespCodec() {
    }

    public static byte[] encodeCommand(String[] parts) {
        byte[][] binary = new byte[parts.length][];
        for (int index = 0; index < parts.length; index++) {
            binary[index] = parts[index].getBytes(StandardCharsets.UTF_8);
        }
        return encodeCommand(binary);
    }

    public static byte[] encodeCommand(byte[][] parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeAscii(out, "*" + parts.length + "\r\n");

        for (byte[] bytes : parts) {
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

    /**
     * Stateful incremental RESP decoder.
     *
     * <p>Input is held in a compactable internal buffer. Once a complete bulk payload is
     * available, it is copied from that buffer into its final value exactly once; aggregates are
     * held by an explicit frame stack. Fragmented input is therefore neither recursively
     * reparsed nor accumulated through repeated array concatenation.</p>
     */
    public static final class Decoder {
        private final RespLimits limits;
        private final Deque<Frame> frames = new ArrayDeque<Frame>();
        private byte[] input;
        private int readIndex;
        private int writeIndex;
        private int lineStart = -1;
        private int lineScanIndex = -1;
        private BulkState bulk;
        private boolean responseInProgress;
        private long responseBytes;
        private long aggregateElements;
        private BobaStrawProtocolException failure;

        public Decoder() {
            this(RespLimits.defaults());
        }

        public Decoder(RespLimits limits) {
            if (limits == null) {
                throw new IllegalArgumentException("limits must not be null");
            }
            this.limits = limits;
            this.input = new byte[Math.min(256, limits.maxBufferedBytes())];
        }

        /** Appends bytes received from the transport. The supplied array may be reused afterwards. */
        public synchronized void feed(byte[] bytes, int length) {
            ensureHealthy();
            if (bytes == null || length < 0 || length > bytes.length) {
                throw fail("Invalid RESP input range");
            }
            if (length == 0) {
                return;
            }

            int unread = writeIndex - readIndex;
            if ((long) unread + length > limits.maxBufferedBytes()) {
                throw fail("RESP decoder buffered input exceeds maxBufferedBytes="
                    + limits.maxBufferedBytes());
            }
            ensureWritable(length, unread);
            System.arraycopy(bytes, 0, input, writeIndex, length);
            writeIndex += length;
        }

        /** Returns one complete top-level RESP value, or {@code null} until more input arrives. */
        public synchronized RespValue poll() {
            ensureHealthy();
            try {
                while (true) {
                    int previousReadIndex = readIndex;
                    RespValue value = decodeStep();
                    if (value != null) {
                        resetCompletedResponse();
                        return value;
                    }
                    if (readIndex == previousReadIndex) {
                        return null;
                    }
                }
            } catch (BobaStrawProtocolException error) {
                if (failure == null) {
                    failure = error;
                }
                throw failure;
            } catch (RuntimeException error) {
                throw fail("Could not decode RESP response", error);
            }
        }

        private RespValue decodeStep() {
            if (bulk != null) {
                return decodeBulk();
            }
            if (readIndex >= writeIndex) {
                return null;
            }

            int lineEnd = findLineEnd();
            if (lineEnd < 0) {
                return null;
            }

            byte marker = input[readIndex];
            int lineValueStart = readIndex + 1;
            int lineValueLength = lineEnd - lineValueStart;
            if (lineValueLength > limits.maxLineLength()) {
                throw fail("RESP line exceeds maxLineLength=" + limits.maxLineLength());
            }

            switch (marker) {
                case '+':
                    return acceptLineValue(
                        new RespValue.SimpleString(utf8(lineValueStart, lineValueLength)), lineEnd
                    );
                case '-':
                    return acceptLineValue(
                        new RespValue.Error(utf8(lineValueStart, lineValueLength)), lineEnd
                    );
                case ':':
                    return acceptLineValue(
                        new RespValue.Number(parseLong(lineValueStart, lineEnd, "integer")), lineEnd
                    );
                case ',':
                    return acceptLineValue(
                        new RespValue.DoubleValue(parseDouble(lineValueStart, lineEnd)), lineEnd
                    );
                case '(':
                    return acceptLineValue(
                        new RespValue.BigNumber(ascii(lineValueStart, lineEnd)), lineEnd
                    );
                case '_':
                    if (lineValueLength != 0) {
                        throw fail("RESP null value must use _\\r\\n");
                    }
                    return acceptLineValue(RespValue.Null.INSTANCE, lineEnd);
                case '#':
                    if (lineValueLength != 1
                        || (input[lineValueStart] != 't' && input[lineValueStart] != 'f')) {
                        throw fail("RESP boolean value must use #t\\r\\n or #f\\r\\n");
                    }
                    return acceptLineValue(
                        new RespValue.BooleanValue(input[lineValueStart] == 't'), lineEnd
                    );
                case '$':
                case '!':
                case '=':
                    return beginBulk(marker, lineValueStart, lineEnd, lineEnd);
                case '*':
                    return beginAggregate(FrameType.ARRAY, lineValueStart, lineEnd, lineEnd);
                case '%':
                    return beginAggregate(FrameType.MAP, lineValueStart, lineEnd, lineEnd);
                case '~':
                    return beginAggregate(FrameType.SET, lineValueStart, lineEnd, lineEnd);
                case '>':
                    return beginAggregate(FrameType.PUSH, lineValueStart, lineEnd, lineEnd);
                case '|':
                    return beginAggregate(FrameType.ATTRIBUTE, lineValueStart, lineEnd, lineEnd);
                default:
                    throw fail("Unsupported RESP marker: " + (char) marker);
            }
        }

        private RespValue beginBulk(byte marker, int start, int end, int lineEnd) {
            long declaredLength = parseLong(start, end, "bulk length");
            if (declaredLength == -1L) {
                if (marker != '$') {
                    throw fail("Null bulk is not valid for RESP marker " + (char) marker);
                }
                consumeLine(lineEnd);
                return acceptValue(RespValue.Null.INSTANCE);
            }
            if (declaredLength < 0L) {
                throw fail("RESP bulk length must not be negative");
            }
            if (declaredLength > limits.maxBulkLength()) {
                throw fail("RESP bulk length exceeds maxBulkLength=" + limits.maxBulkLength());
            }
            if (declaredLength > Integer.MAX_VALUE) {
                throw fail("RESP bulk length is too large for this JVM");
            }

            consumeLine(lineEnd);
            bulk = new BulkState(marker, (int) declaredLength);
            return null;
        }

        private RespValue beginAggregate(FrameType type, int start, int end, int lineEnd) {
            long declaredCount = parseLong(start, end, "aggregate length");
            if (declaredCount == -1L) {
                if (type == FrameType.ATTRIBUTE) {
                    throw fail("RESP attribute must not be null");
                }
                consumeLine(lineEnd);
                return acceptValue(RespValue.Null.INSTANCE);
            }
            if (declaredCount < 0L) {
                throw fail("RESP aggregate length must not be negative");
            }

            if (frames.size() >= limits.maxNestingDepth()) {
                throw fail("RESP aggregate exceeds maxNestingDepth=" + limits.maxNestingDepth());
            }
            long childCount = aggregateChildCount(type, declaredCount);
            reserveAggregateElements(childCount);
            consumeLine(lineEnd);
            if (childCount == 0L) {
                return acceptValue(new Frame(type, 0).toValue());
            }
            frames.push(new Frame(type, (int) childCount));
            return null;
        }

        private long aggregateChildCount(FrameType type, long declaredCount) {
            long multiplier = type == FrameType.MAP || type == FrameType.ATTRIBUTE ? 2L : 1L;
            if (declaredCount > Long.MAX_VALUE / multiplier) {
                throw fail("RESP aggregate length overflows child count");
            }
            long childCount = declaredCount * multiplier;
            if (type == FrameType.ATTRIBUTE) {
                if (childCount == Long.MAX_VALUE) {
                    throw fail("RESP attribute length overflows child count");
                }
                childCount++;
            }
            if (childCount > Integer.MAX_VALUE) {
                throw fail("RESP aggregate contains too many child values");
            }
            return childCount;
        }

        private void reserveAggregateElements(long count) {
            if (count > limits.maxAggregateElements() - aggregateElements) {
                throw fail("RESP aggregate exceeds maxAggregateElements="
                    + limits.maxAggregateElements());
            }
            aggregateElements += count;
        }

        private RespValue decodeBulk() {
            int available = writeIndex - readIndex;
            int remaining = bulk.bytes.length - bulk.offset;
            int copied = Math.min(available, remaining);
            if (copied > 0) {
                System.arraycopy(input, readIndex, bulk.bytes, bulk.offset, copied);
                bulk.offset += copied;
                consume(copied);
            }
            if (bulk.offset < bulk.bytes.length) {
                checkIncompleteResponseSize();
                return null;
            }
            if (writeIndex - readIndex < 2) {
                checkIncompleteResponseSize();
                return null;
            }
            if (input[readIndex] != '\r' || input[readIndex + 1] != '\n') {
                throw fail("RESP bulk payload is not followed by CRLF");
            }
            consume(2);

            BulkState completed = bulk;
            bulk = null;
            return acceptValue(completed.toValue());
        }

        private RespValue acceptLineValue(RespValue value, int lineEnd) {
            consumeLine(lineEnd);
            return acceptValue(value);
        }

        private RespValue acceptValue(RespValue value) {
            while (!frames.isEmpty()) {
                Frame frame = frames.peek();
                frame.values.add(value);
                if (frame.values.size() < frame.expectedChildCount) {
                    return null;
                }
                frames.pop();
                value = frame.toValue();
            }
            return value;
        }

        private int findLineEnd() {
            if (lineStart != readIndex) {
                lineStart = readIndex;
                lineScanIndex = readIndex + 1;
            }
            for (int index = lineScanIndex; index < writeIndex; index++) {
                byte current = input[index];
                if (current == '\n') {
                    throw fail("RESP line contains a LF without a preceding CR");
                }
                if (current == '\r') {
                    if (index + 1 == writeIndex) {
                        lineScanIndex = index;
                        checkIncompleteLineLength(true);
                        checkIncompleteResponseSize();
                        return -1;
                    }
                    if (input[index + 1] != '\n') {
                        throw fail("RESP line CR must be followed by LF");
                    }
                    return index;
                }
            }
            lineScanIndex = writeIndex;
            checkIncompleteLineLength(false);
            checkIncompleteResponseSize();
            return -1;
        }

        private void checkIncompleteLineLength(boolean endsWithCr) {
            int valueLength = writeIndex - readIndex - 1;
            if (endsWithCr) {
                valueLength--;
            }
            if (valueLength > limits.maxLineLength()) {
                throw fail("RESP line exceeds maxLineLength=" + limits.maxLineLength());
            }
        }

        private void consumeLine(int lineEnd) {
            consume(lineEnd + 2 - readIndex);
            clearLineSearch();
        }

        private void consume(int count) {
            if (!responseInProgress) {
                responseInProgress = true;
            }
            responseBytes += count;
            if (responseBytes > limits.maxResponseBytes()) {
                throw fail("RESP response exceeds maxResponseBytes=" + limits.maxResponseBytes());
            }
            readIndex += count;
        }

        private void checkIncompleteResponseSize() {
            long buffered = writeIndex - readIndex;
            if (responseBytes + buffered > limits.maxResponseBytes()) {
                throw fail("RESP response exceeds maxResponseBytes=" + limits.maxResponseBytes());
            }
        }

        private long parseLong(int start, int end, String kind) {
            if (start >= end) {
                throw fail("RESP " + kind + " is empty");
            }

            int index = start;
            boolean negative = false;
            byte first = input[index];
            if (first == '-') {
                negative = true;
                index++;
            } else if (first == '+') {
                index++;
            }
            if (index == end) {
                throw fail("Invalid RESP " + kind);
            }

            // Accumulate negatively, matching Long.parseLong's overflow-safe representation,
            // while avoiding a temporary String for every numeric RESP header.
            long limit = negative ? Long.MIN_VALUE : -Long.MAX_VALUE;
            long multiplyLimit = limit / 10L;
            long result = 0L;
            while (index < end) {
                int digit = (input[index] & 0xff) - '0';
                if (digit < 0 || digit > 9 || result < multiplyLimit) {
                    throw fail("Invalid RESP " + kind);
                }
                result *= 10L;
                if (result < limit + digit) {
                    throw fail("Invalid RESP " + kind);
                }
                result -= digit;
                index++;
            }
            return negative ? result : -result;
        }

        private double parseDouble(int start, int end) {
            String value = ascii(start, end);
            if ("inf".equals(value)) {
                return Double.POSITIVE_INFINITY;
            }
            if ("-inf".equals(value)) {
                return Double.NEGATIVE_INFINITY;
            }
            if ("nan".equals(value)) {
                return Double.NaN;
            }
            try {
                return Double.parseDouble(value);
            } catch (NumberFormatException error) {
                throw fail("Invalid RESP double", error);
            }
        }

        private String utf8(int start, int length) {
            return new String(input, start, length, StandardCharsets.UTF_8);
        }

        private String ascii(int start, int end) {
            return new String(input, start, end - start, StandardCharsets.US_ASCII);
        }

        private void ensureWritable(int length, int unread) {
            if (input.length - writeIndex >= length) {
                return;
            }
            if (readIndex > 0) {
                compactInput(unread);
                if (input.length - writeIndex >= length) {
                    return;
                }
            }

            int required = unread + length;
            int capacity = input.length;
            while (capacity < required) {
                int next = capacity < 1024 ? capacity * 2 : capacity + capacity / 2;
                if (next <= capacity || next > limits.maxBufferedBytes()) {
                    capacity = limits.maxBufferedBytes();
                    break;
                }
                capacity = next;
            }
            if (capacity < required) {
                throw fail("RESP decoder buffered input exceeds maxBufferedBytes="
                    + limits.maxBufferedBytes());
            }

            byte[] next = new byte[capacity];
            System.arraycopy(input, readIndex, next, 0, unread);
            adjustLineSearchAfterCompaction(readIndex);
            input = next;
            readIndex = 0;
            writeIndex = unread;
        }

        private void compactInput(int unread) {
            int shift = readIndex;
            if (unread > 0) {
                System.arraycopy(input, readIndex, input, 0, unread);
            }
            adjustLineSearchAfterCompaction(shift);
            readIndex = 0;
            writeIndex = unread;
        }

        private void adjustLineSearchAfterCompaction(int shift) {
            if (lineStart >= 0) {
                lineStart -= shift;
                lineScanIndex -= shift;
            }
        }

        private void clearLineSearch() {
            lineStart = -1;
            lineScanIndex = -1;
        }

        private void resetCompletedResponse() {
            responseInProgress = false;
            responseBytes = 0L;
            aggregateElements = 0L;
            if (readIndex == writeIndex) {
                readIndex = 0;
                writeIndex = 0;
                clearLineSearch();
            }
        }

        private void ensureHealthy() {
            if (failure != null) {
                throw failure;
            }
        }

        private BobaStrawProtocolException fail(String message) {
            return fail(message, null);
        }

        private BobaStrawProtocolException fail(String message, Throwable cause) {
            if (failure == null) {
                failure = cause == null
                    ? new BobaStrawProtocolException(message)
                    : new BobaStrawProtocolException(message, cause);
            }
            return failure;
        }

        private enum FrameType {
            ARRAY,
            MAP,
            SET,
            PUSH,
            ATTRIBUTE
        }

        private static final class Frame {
            private final FrameType type;
            private final int expectedChildCount;
            private final List<RespValue> values = new ArrayList<RespValue>();

            private Frame(FrameType type, int expectedChildCount) {
                this.type = type;
                this.expectedChildCount = expectedChildCount;
            }

            private RespValue toValue() {
                if (type == FrameType.MAP) {
                    return new RespValue.MapValue(mapValues(values, values.size()));
                }
                if (type == FrameType.SET) {
                    return new RespValue.SetValue(values);
                }
                if (type == FrameType.PUSH) {
                    return new RespValue.Push(values);
                }
                if (type == FrameType.ATTRIBUTE) {
                    int attributeChildCount = values.size() - 1;
                    return new RespValue.Attribute(
                        new RespValue.MapValue(mapValues(values, attributeChildCount)),
                        values.get(attributeChildCount)
                    );
                }
                return new RespValue.Array(values);
            }

            private static Map<RespValue, RespValue> mapValues(
                List<RespValue> values,
                int childCount
            ) {
                if ((childCount & 1) != 0) {
                    throw new IllegalStateException("RESP map must contain key/value pairs");
                }
                Map<RespValue, RespValue> result = new LinkedHashMap<RespValue, RespValue>();
                for (int index = 0; index < childCount; index += 2) {
                    result.put(values.get(index), values.get(index + 1));
                }
                return result;
            }
        }

        private static final class BulkState {
            private final byte marker;
            private final byte[] bytes;
            private int offset;

            private BulkState(byte marker, int length) {
                this.marker = marker;
                this.bytes = new byte[length];
            }

            private RespValue toValue() {
                if (marker == '!') {
                    return new RespValue.BlobError(bytes);
                }
                if (marker == '=') {
                    int separator = -1;
                    for (int index = 0; index < bytes.length; index++) {
                        if (bytes[index] == ':') {
                            separator = index;
                            break;
                        }
                    }
                    if (separator <= 0) {
                        throw new BobaStrawProtocolException("Invalid RESP verbatim string");
                    }
                    byte[] value = new byte[bytes.length - separator - 1];
                    System.arraycopy(bytes, separator + 1, value, 0, value.length);
                    return new RespValue.VerbatimString(
                        new String(bytes, 0, separator, StandardCharsets.US_ASCII),
                        value
                    );
                }
                return new RespValue.BlobString(bytes);
            }
        }
    }
}
