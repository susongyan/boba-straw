package io.github.susongyan.bobastraw.protocol;

import io.github.susongyan.bobastraw.BobaStrawProtocolException;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RespCodecTest {
    @Test void decodesFragmentedBlobString() {
        RespCodec.Decoder decoder = new RespCodec.Decoder();
        byte[] reply = "$5\r\nhello\r\n".getBytes(StandardCharsets.US_ASCII);
        for (byte b : reply) decoder.feed(new byte[] { b }, 1);
        assertEquals("hello", decoder.poll().asString());
        assertNull(decoder.poll());
    }

    @Test void decodesResp3PushWithoutLosingFollowingReply() {
        RespCodec.Decoder decoder = new RespCodec.Decoder();
        byte[] reply = ">3\r\n+message\r\n+events\r\n+hi\r\n+OK\r\n".getBytes(StandardCharsets.US_ASCII);
        decoder.feed(reply, reply.length);
        assertTrue(decoder.poll() instanceof RespValue.Push);
        assertEquals("OK", decoder.poll().asString());
    }

    @Test void encodesCommand() {
        String encoded = new String(RespCodec.encodeCommand(new String[] { "SET", "tea", "boba" }), StandardCharsets.US_ASCII);
        assertEquals("*3\r\n$3\r\nSET\r\n$3\r\ntea\r\n$4\r\nboba\r\n", encoded);
    }

    @Test void decodesResp3AttributeAndReplyTogether() {
        RespCodec.Decoder decoder = new RespCodec.Decoder();
        byte[] reply = "|1\r\n+source\r\n+cache\r\n$2\r\nok\r\n".getBytes(StandardCharsets.US_ASCII);
        decoder.feed(reply, reply.length);
        RespValue.Attribute attribute = (RespValue.Attribute) decoder.poll();
        assertEquals("ok", attribute.value.asString());
        assertEquals(1, attribute.attributes.values.size());
    }

    @Test
    void decodesAdditionalResp3ScalarTypes() {
        RespCodec.Decoder decoder = new RespCodec.Decoder();
        byte[] input = "!5\r\nERR!!\r\n=9\r\ntxt:hello\r\n(12345678901234567890\r\n"
            .getBytes(StandardCharsets.UTF_8);
        decoder.feed(input, input.length);

        assertEquals("ERR!!", ((RespValue.BlobError) decoder.poll()).message());
        RespValue.VerbatimString verbatim = (RespValue.VerbatimString) decoder.poll();
        assertEquals("txt", verbatim.format);
        assertEquals("hello", verbatim.asString());
        assertEquals("12345678901234567890", ((RespValue.BigNumber) decoder.poll()).value);
    }

    @Test
    void decodesRespIntegerLongBoundaries() {
        RespCodec.Decoder decoder = new RespCodec.Decoder();
        byte[] input = ascii(
            ":-9223372036854775808\r\n:9223372036854775807\r\n"
        );
        decoder.feed(input, input.length);

        assertEquals(Long.MIN_VALUE, decoder.poll().asLong());
        assertEquals(Long.MAX_VALUE, decoder.poll().asLong());
    }

    @Test
    void encodesBinaryCommandArgumentsWithoutTextConversion() {
        byte[] encoded = RespCodec.encodeCommand(new byte[][] {
            new byte[] { 'S', 'E', 'T' },
            new byte[] { 0, 1, (byte) 0xff }
        });
        assertEquals('*', encoded[0]);
        assertTrue(new String(encoded, StandardCharsets.ISO_8859_1)
            .contains("$3\r\nSET\r\n$3\r\n"));
        assertEquals(0, encoded[17]);
        assertEquals((byte) 0xff, encoded[19]);
    }

    @Test
    void decodesNestedResp3AttributeAcrossEveryWireSplitWithoutPublishingEarly() {
        byte[] wire = ascii(
            "|1\r\n+source\r\n+cache\r\n*2\r\n$3\r\none\r\n%1\r\n+tea\r\n+milk\r\n"
        );

        for (int split = 1; split < wire.length; split++) {
            RespCodec.Decoder decoder = new RespCodec.Decoder();
            decoder.feed(Arrays.copyOfRange(wire, 0, split), split);
            assertNull(decoder.poll(), "Attribute must remain atomic at split " + split);
            byte[] remaining = Arrays.copyOfRange(wire, split, wire.length);
            decoder.feed(remaining, remaining.length);

            RespValue.Attribute result = (RespValue.Attribute) decoder.poll();
            assertEquals("source", result.attributes.values.keySet().iterator().next().asString());
            assertEquals("cache", result.attributes.values.values().iterator().next().asString());
            RespValue.Array payload = (RespValue.Array) result.value;
            assertEquals("one", payload.values.get(0).asString());
            RespValue.MapValue nested = (RespValue.MapValue) payload.values.get(1);
            assertEquals("milk", nested.values.values().iterator().next().asString());
            assertNull(decoder.poll());
        }
    }

    @Test
    void copiesFragmentedBulkPayloadBeforeTheSourceArrayCanBeReused() {
        RespCodec.Decoder decoder = new RespCodec.Decoder();
        byte[] firstChunk = ascii("$5\r\nhe");
        decoder.feed(firstChunk, firstChunk.length);
        Arrays.fill(firstChunk, (byte) 'x');
        byte[] secondChunk = ascii("llo\r\n");
        decoder.feed(secondChunk, secondChunk.length);
        Arrays.fill(secondChunk, (byte) 'y');

        RespValue.BlobString value = (RespValue.BlobString) decoder.poll();
        assertEquals("hello", value.asString());
        assertArrayEquals(ascii("hello"), value.value);
    }

    @Test
    void decodesALargeFragmentedBulkAndTheFollowingReplyInWireOrder() {
        byte[] payload = new byte[16 * 1024];
        Arrays.fill(payload, (byte) 'b');
        ByteArrayOutputStream wire = new ByteArrayOutputStream();
        byte[] header = ascii("$" + payload.length + "\r\n");
        wire.write(header, 0, header.length);
        wire.write(payload, 0, payload.length);
        byte[] suffix = ascii("\r\n+PONG\r\n");
        wire.write(suffix, 0, suffix.length);

        RespCodec.Decoder decoder = new RespCodec.Decoder();
        byte[] bytes = wire.toByteArray();
        for (int offset = 0; offset < bytes.length; offset += 37) {
            int length = Math.min(37, bytes.length - offset);
            byte[] chunk = Arrays.copyOfRange(bytes, offset, offset + length);
            decoder.feed(chunk, chunk.length);
        }

        RespValue.BlobString blob = (RespValue.BlobString) decoder.poll();
        assertArrayEquals(payload, blob.value);
        assertEquals("PONG", decoder.poll().asString());
        assertNull(decoder.poll());
    }

    @Test
    void rejectsMalformedWireAndKeepsTheProtocolFailureTerminal() {
        assertMalformed("#x\r\n");
        assertMalformed("_not-null\r\n");
        assertMalformed("+one\n+two\r\n");
        assertMalformed("+one\rX");
        assertMalformed("$1\r\na\rX");
        assertMalformed("$-2\r\n");
        assertMalformed("$9223372036854775808\r\n");
        assertMalformed("=3\r\nabc\r\n");
    }

    @Test
    void acceptsTheCrLfLineTerminatorWhenItArrivesInSeparateFragments() {
        RespCodec.Decoder decoder = new RespCodec.Decoder();
        byte[] beforeLf = ascii("+PONG\r");
        decoder.feed(beforeLf, beforeLf.length);
        assertNull(decoder.poll());
        decoder.feed(ascii("\n"), 1);
        assertEquals("PONG", decoder.poll().asString());
    }

    @Test
    void decodesFragmentedAttributePushAndNormalReplyInWireOrder() {
        byte[] wire = ascii(
            "|1\r\n+source\r\n+cache\r\n>3\r\n+message\r\n+events\r\n+tea\r\n+PONG\r\n"
        );
        RespCodec.Decoder decoder = new RespCodec.Decoder();
        List<RespValue> decoded = new ArrayList<RespValue>();

        for (byte value : wire) {
            decoder.feed(new byte[] { value }, 1);
            RespValue next;
            while ((next = decoder.poll()) != null) {
                decoded.add(next);
            }
        }

        assertEquals(2, decoded.size());
        RespValue.Attribute attribute = (RespValue.Attribute) decoded.get(0);
        assertTrue(attribute.value instanceof RespValue.Push);
        assertEquals("PONG", decoded.get(1).asString());
    }

    @Test
    void enforcesProtocolLimitsAtBoundaries() {
        RespLimits bulkLimit = RespLimits.builder()
            .maxBulkLength(3)
            .build();
        RespCodec.Decoder permittedBulk = new RespCodec.Decoder(bulkLimit);
        byte[] permitted = ascii("$3\r\ntea\r\n");
        permittedBulk.feed(permitted, permitted.length);
        assertEquals("tea", permittedBulk.poll().asString());
        assertLimitFailure(bulkLimit, "$4\r\nmilk\r\n");

        RespLimits aggregateLimit = RespLimits.builder()
            .maxAggregateElements(2)
            .build();
        assertLimitFailure(aggregateLimit, "*3\r\n+one\r\n+two\r\n+three\r\n");

        RespLimits depthLimit = RespLimits.builder()
            .maxNestingDepth(1)
            .build();
        assertLimitFailure(depthLimit, "*1\r\n*0\r\n");

        RespLimits lineLimit = RespLimits.builder()
            .maxLineLength(3)
            .build();
        RespCodec.Decoder permittedLine = new RespCodec.Decoder(lineLimit);
        byte[] permittedLineWire = ascii("+abc\r");
        permittedLine.feed(permittedLineWire, permittedLineWire.length);
        assertNull(permittedLine.poll());
        permittedLine.feed(ascii("\n"), 1);
        assertEquals("abc", permittedLine.poll().asString());
        assertLimitFailure(lineLimit, "+abcd\r\n");

        RespLimits responseLimit = RespLimits.builder()
            .maxBufferedBytes(16)
            .maxResponseBytes(5)
            .maxBulkLength(3)
            .maxLineLength(3)
            .build();
        assertLimitFailure(responseLimit, "+abc\r\n");

        RespLimits bufferedLimit = RespLimits.builder()
            .maxBufferedBytes(5)
            .maxResponseBytes(5)
            .maxBulkLength(2)
            .maxLineLength(2)
            .build();
        RespCodec.Decoder limitedBuffer = new RespCodec.Decoder(bufferedLimit);
        BobaStrawProtocolException error = assertThrows(
            BobaStrawProtocolException.class,
            () -> limitedBuffer.feed(ascii("+abcd\r\n"), 7)
        );
        assertSame(error, assertThrows(BobaStrawProtocolException.class, limitedBuffer::poll));
    }

    private static void assertMalformed(String wire) {
        RespCodec.Decoder decoder = new RespCodec.Decoder();
        byte[] bytes = ascii(wire);
        decoder.feed(bytes, bytes.length);
        BobaStrawProtocolException error = assertThrows(
            BobaStrawProtocolException.class,
            decoder::poll
        );
        assertSame(error, assertThrows(BobaStrawProtocolException.class, decoder::poll));
    }

    private static void assertLimitFailure(RespLimits limits, String wire) {
        RespCodec.Decoder decoder = new RespCodec.Decoder(limits);
        byte[] bytes = ascii(wire);
        decoder.feed(bytes, bytes.length);
        assertThrows(BobaStrawProtocolException.class, decoder::poll);
    }

    private static byte[] ascii(String value) {
        return value.getBytes(StandardCharsets.US_ASCII);
    }
}
