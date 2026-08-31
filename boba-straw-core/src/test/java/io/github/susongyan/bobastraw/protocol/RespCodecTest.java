package io.github.susongyan.bobastraw.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

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
}
