package io.github.susongyan.bobastraw;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Real binary String commands on Redis 5/6.2/7.4 and Valkey 8.1, RESP2/AUTO. */
@EnabledIfSystemProperty(named = "boba.straw.runCompatibility", matches = "true")
class BinaryStringCompatibilityTest {
    @Test
    void binaryBatchesRangesAndErrorsAcrossServerMatrix() throws Exception {
        for (int port : new int[] {16379, 16380, 16381, 16382}) {
            for (ProtocolVersion protocol : new ProtocolVersion[] {ProtocolVersion.RESP2, ProtocolVersion.AUTO}) {
                try (BobaStrawClient client = client(port, protocol)) {
                    byte[][] keys = keys();
                    BobaStrawBinaryCommands binary = client.binary();
                    byte[] value = {(byte) 0xff, 0, (byte) 0xc3, 0x28};
                    try {
                        assertArrayEquals(ascii("OK"), await(binary.mset(keys[0], value, keys[1], new byte[0])));
                        List<byte[]> values = await(binary.mget(keys[0], keys[2], keys[1], keys[0]));
                        assertEquals(4, values.size());
                        assertArrayEquals(value, values.get(0));
                        assertNull(values.get(1));
                        assertArrayEquals(new byte[0], values.get(2));
                        assertArrayEquals(value, values.get(3));
                        assertFalse(await(binary.msetNx(keys[0], new byte[0], keys[2], value)));
                        assertNull(await(binary.get(keys[2])));
                        assertTrue(await(binary.msetNx(keys[2], value)));
                        assertEquals(Long.valueOf(0), await(binary.strlen(keys[3])));
                        assertEquals(Long.valueOf(4), await(binary.append(keys[3], value)));
                        assertEquals(Long.valueOf(4), await(binary.strlen(keys[3])));
                        assertArrayEquals(new byte[] {(byte) 0xc3, 0x28}, await(binary.getRange(keys[3], -2, -1)));
                        assertArrayEquals(new byte[0], await(binary.getRange(keys[3], 99, 100)));
                        assertEquals(Long.valueOf(8), await(binary.setRange(keys[3], 6, new byte[] {0, (byte) 0xfe})));
                        assertArrayEquals(new byte[] {(byte) 0xff, 0, (byte) 0xc3, 0x28, 0, 0, 0, (byte) 0xfe},
                            await(binary.get(keys[3])));

                        await(binary.del(keys[3]));
                        assertArrayEquals(new byte[0], await(binary.getRange(keys[3], 0, -1)));
                        await(client.executeBinaryAsync(ascii("LPUSH"), keys[3], value));
                        assertNull(await(binary.mget(keys[3])).get(0));
                        assertServerError(binary.append(keys[3], value), "WRONGTYPE");
                        assertServerError(binary.strlen(keys[3]), "WRONGTYPE");
                        assertServerError(binary.getRange(keys[3], 0, -1), "WRONGTYPE");
                        assertServerError(binary.setRange(keys[3], 0, value), "WRONGTYPE");
                        assertEquals("PONG", client.sync().ping());
                    } finally {
                        await(binary.del(keys));
                    }
                }
            }
        }
    }

    @Test
    void binarySetOptionsRespectVersionAndConditionalResultSemantics() throws Exception {
        for (int port : new int[] {16379, 16380, 16381, 16382}) {
            for (ProtocolVersion protocol : new ProtocolVersion[] {ProtocolVersion.RESP2, ProtocolVersion.AUTO}) {
                try (BobaStrawClient client = client(port, protocol)) {
                    byte[][] keys = keys();
                    BobaStrawBinaryCommands binary = client.binary();
                    byte[] old = {(byte) 0xff, 0};
                    try {
                        assertNull(await(binary.set(keys[0], old, SetArgs.xx())));
                        assertArrayEquals(ascii("OK"), await(binary.set(keys[0], old, SetArgs.nx().ex(60))));
                        assertNull(await(binary.set(keys[0], new byte[0], SetArgs.nx())));
                        assertArrayEquals(old, await(binary.get(keys[0])));
                        assertArrayEquals(ascii("OK"), await(binary.set(keys[0], old, SetArgs.xx().px(60000))));
                        assertTrue(await(client.executeBinaryAsync(ascii("PTTL"), keys[0])).asLong() > 0);
                        if (port == 16379) {
                            assertServerError(binary.set(keys[0], new byte[0], SetArgs.none().returnOldValue()), "ERR");
                            assertServerError(binary.set(keys[0], old, SetArgs.none().keepTtl()), "ERR");
                        } else {
                            assertArrayEquals(old, await(binary.set(keys[0], new byte[0],
                                SetArgs.none().keepTtl().returnOldValue())));
                            assertTrue(await(client.executeBinaryAsync(ascii("PTTL"), keys[0])).asLong() > 0);
                            assertArrayEquals(new byte[0], await(binary.set(keys[0], old,
                                SetArgs.none().exAt(System.currentTimeMillis() / 1000 + 60).returnOldValue())));
                            assertArrayEquals(ascii("OK"), await(binary.set(keys[0], old,
                                SetArgs.none().pxAt(System.currentTimeMillis() + 60000))));
                            assertNull(await(binary.set(keys[1], old, SetArgs.xx().returnOldValue())));
                            assertNull(await(binary.get(keys[1])));
                            assertNull(await(binary.set(keys[1], old, SetArgs.none().returnOldValue())));
                            assertArrayEquals(old, await(binary.get(keys[1])));
                            if (port == 16380) {
                                assertServerError(binary.set(keys[0], new byte[0], SetArgs.nx().returnOldValue()), "ERR");
                            } else {
                                assertArrayEquals(old, await(binary.set(keys[0], new byte[0], SetArgs.nx().returnOldValue())));
                                assertArrayEquals(old, await(binary.get(keys[0])), "NX GET returns old data without writing");
                            }
                        }
                        assertEquals("PONG", client.sync().ping());
                    } finally {
                        await(binary.del(keys));
                    }
                }
            }
        }
    }

    private static BobaStrawClient client(int port, ProtocolVersion protocol) {
        return BobaStrawClient.builder().endpoint("127.0.0.1", port).protocol(protocol)
            .commandTimeout(Duration.ofSeconds(3)).build();
    }

    private static byte[][] keys() {
        byte[] prefix = ascii("boba:binary:" + UUID.randomUUID() + ":");
        byte[][] keys = new byte[4][];
        for (int index = 0; index < keys.length; index++) {
            keys[index] = Arrays.copyOf(prefix, prefix.length + 3);
            keys[index][prefix.length] = (byte) 0xff;
            keys[index][prefix.length + 1] = 0;
            keys[index][prefix.length + 2] = (byte) index;
        }
        return keys;
    }

    private static byte[] ascii(String text) {
        return text.getBytes(StandardCharsets.US_ASCII);
    }

    private static <T> T await(CompletionStage<T> stage) throws Exception {
        return stage.toCompletableFuture().get(4, TimeUnit.SECONDS);
    }

    private static void assertServerError(CompletionStage<?> stage, String text) {
        ExecutionException failure = assertThrows(ExecutionException.class, () -> await(stage));
        assertTrue(failure.getCause() instanceof BobaStrawServerException, failure.toString());
        assertTrue(failure.getCause().getMessage().contains(text), failure.toString());
    }
}
