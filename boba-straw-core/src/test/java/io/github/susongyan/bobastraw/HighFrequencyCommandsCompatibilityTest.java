package io.github.susongyan.bobastraw;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "boba.straw.runCompatibility", matches = "true")
class HighFrequencyCommandsCompatibilityTest {
    @Test
    void binaryKeyTtlCounterAndBitCommands() throws Exception {
        matrix((client, keys) -> {
            BobaStrawBinaryCommands b = client.binary();
            byte[] k = binary(keys[0]);
            assertEquals("none", await(b.type(k)));
            number(-2, b.ttl(k));
            number(-2, b.pttl(k));
            assertFalse(await(b.exists(k)));
            number(1, b.incr(k));
            number(4, b.incrBy(k, 3));
            number(3, b.decr(k));
            number(-2, b.decrBy(k, 5));
            assertEquals("string", await(b.type(k)));
            assertTrue(await(b.exists(k)));
            number(2, b.existsCount(k, k, binary(keys[1])));
            number(-1, b.ttl(k));
            number(1, b.expire(k, 60));
            assertTrue(await(b.ttl(k)) > 0);
            number(1, b.persist(k));
            number(-1, b.pttl(k));
            number(1, b.pexpire(k, 60000));
            assertTrue(await(b.pttl(k)) > 0);
            number(1, b.expireAt(k, System.currentTimeMillis() / 1000 + 60));
            number(1, b.pexpireAt(k, System.currentTimeMillis() + 60000));
            number(1, b.expire(k, 0));
            assertFalse(await(b.exists(k)));
            number(0, b.persist(k));
            number(0, b.getBit(k, 0));
            number(0, b.setBit(k, 31, 1));
            number(1, b.getBit(k, 31));
            number(1, b.bitCount(k));
            number(1, b.bitCount(k, -1, -1));
            number(0, b.bitCount(k, 0, 0));
            serverError(b.setBit(k, -1, 1));
            serverError(b.setBit(k, 0, 2));
            await(b.set(k, ascii(Long.toString(Long.MAX_VALUE))));
            serverError(b.incr(k));
            assertArrayEquals(ascii(Long.toString(Long.MAX_VALUE)), await(b.get(k)));
            await(b.set(k, new byte[] {(byte) 0xff}));
            serverError(b.incrBy(k, 1));
            number(1, b.unlink(k, binary(keys[1])));
            number(-2, b.ttl(k));
        });
    }

    @Test
    void binaryHashListSetAndSortedSet() throws Exception {
        matrix((client, keys) -> {
            BobaStrawBinaryCommands b = client.binary();
            byte[] h = binary(keys[0]);
            byte[] l = binary(keys[1]);
            byte[] s = binary(keys[2]);
            byte[] z = binary(keys[3]);
            byte[] field = {(byte) 0xff, 0};
            byte[] value = {0, (byte) 0xc3, 0x28};
            byte[] empty = new byte[0];
            assertTrue(await(b.hgetall(h)).isEmpty());
            assertNull(await(b.hget(h, field)));
            number(1, b.hset(h, field, value));
            number(0, b.hset(h, field, value));
            number(1, b.hset(h, empty, empty));
            assertArrayEquals(value, await(b.hget(h, field)));
            List<byte[]> values = await(b.hmget(h, field, empty, ascii("missing"), field));
            assertArrayEquals(value, values.get(0));
            assertArrayEquals(empty, values.get(1));
            assertNull(values.get(2));
            assertArrayEquals(value, values.get(3));
            List<Map.Entry<byte[], byte[]>> pairs = await(b.hgetall(h));
            assertEquals(2, pairs.size());
            assertTrue(pairs.stream().anyMatch(e -> Arrays.equals(field, e.getKey()) && Arrays.equals(value, e.getValue())));
            number(2, b.hlen(h));
            assertTrue(await(b.hexists(h, field)));
            assertFalse(await(b.hexists(h, ascii("missing"))));
            number(1, b.hdel(h, field));
            number(3, b.hincrBy(h, field, 3));
            serverError(b.hincrBy(h, empty, 1));

            assertNull(await(b.lpop(l)));
            assertNull(await(b.rpop(l)));
            number(2, b.lpush(l, value, empty));
            number(3, b.rpush(l, field));
            number(3, b.llen(l));
            assertArrayEquals(empty, await(b.lrange(l, 0, -1)).get(0));
            assertArrayEquals(empty, await(b.lpop(l)));
            assertArrayEquals(field, await(b.rpop(l)));
            serverError(b.hget(l, field));

            assertTrue(await(b.smembers(s)).isEmpty());
            number(2, b.sadd(s, value, value.clone(), empty));
            number(2, b.scard(s));
            assertTrue(await(b.sismember(s, value)));
            assertFalse(await(b.sismember(s, field)));
            List<byte[]> members = await(b.smembers(s));
            assertEquals(2, members.size());
            assertTrue(members.stream().anyMatch(v -> Arrays.equals(value, v)));
            number(1, b.srem(s, value));
            serverError(b.scard(l));

            assertNull(await(b.zscore(z, value)));
            assertNull(await(b.zrank(z, value)));
            number(1, b.zadd(z, 1.25, value));
            number(1, b.zadd(z, -2, empty));
            number(2, b.zcard(z));
            assertEquals(Double.valueOf(1.25), await(b.zscore(z, value)));
            number(1, b.zrank(z, value));
            assertArrayEquals(empty, await(b.zrange(z, 0, -1)).get(0));
            number(2, b.zrem(z, empty, value));
            assertTrue(await(b.zrange(z, 0, -1)).isEmpty());
            serverError(b.zscore(l, value));
        });
    }

    @Test
    void newStringMethodsWorkForBothFacades() throws Exception {
        matrix((client, keys) -> {
            BobaStrawSyncCommands s = client.sync();
            BobaStrawAsyncCommands a = client.async();
            String h = keys[0], l = keys[1], set = keys[2], z = keys[3];
            s.hset(h, "f", "1");
            assertEquals(Arrays.asList("1", null), s.hmget(h, "f", "missing"));
            assertEquals(Arrays.asList("1", null), await(a.hmget(h, "f", "missing")));
            assertTrue(s.hexists(h, "f"));
            assertTrue(await(a.hexists(h, "f")));
            assertEquals(Long.valueOf(1), s.hlen(h));
            number(1, a.hlen(h));
            assertEquals(Long.valueOf(2), s.hincrBy(h, "f", 1));
            number(3, a.hincrBy(h, "f", 1));
            assertEquals(Long.valueOf(1), s.hdel(h, "f"));
            number(0, a.hdel(h, "f"));
            s.rpush(l, "a", "b", "c", "d");
            assertEquals(Long.valueOf(4), s.llen(l));
            number(4, a.llen(l));
            assertEquals("a", s.lpop(l));
            assertEquals("b", await(a.lpop(l)));
            assertEquals("d", s.rpop(l));
            assertEquals("c", await(a.rpop(l)));
            assertNull(s.lpop(l));
            s.sadd(set, "a", "b");
            assertEquals(Long.valueOf(2), s.scard(set));
            number(2, a.scard(set));
            assertTrue(s.sismember(set, "a"));
            assertTrue(await(a.sismember(set, "a")));
            assertEquals(Long.valueOf(1), s.srem(set, "a"));
            number(1, a.srem(set, "b"));
            s.zadd(z, 1.5, "a");
            s.zadd(z, 2, "b");
            assertEquals(Long.valueOf(2), s.zcard(z));
            number(2, a.zcard(z));
            assertEquals(Double.valueOf(1.5), s.zscore(z, "a"));
            assertEquals(Double.valueOf(1.5), await(a.zscore(z, "a")));
            assertEquals(Long.valueOf(0), s.zrank(z, "a"));
            number(0, a.zrank(z, "a"));
            assertNull(s.zscore(z, "missing"));
            assertNull(await(a.zrank(z, "missing")));
            assertEquals(Long.valueOf(1), s.zrem(z, "a"));
            number(1, a.zrem(z, "b"));
            s.set(l, "a");
            assertEquals(Long.valueOf(3), s.bitCount(l));
            assertEquals(Long.valueOf(3), s.bitCount(l, 0, 0));
            number(3, a.bitCount(l));
            number(3, a.bitCount(l, 0, 0));
        });
    }

    private static void matrix(Scenario scenario) throws Exception {
        for (int port : new int[] {16379, 16380, 16381, 16382}) {
            for (ProtocolVersion protocol : new ProtocolVersion[] {ProtocolVersion.RESP2, ProtocolVersion.AUTO}) {
                try (BobaStrawClient client = BobaStrawClient.builder().endpoint("127.0.0.1", port)
                    .protocol(protocol).commandTimeout(Duration.ofSeconds(3)).build()) {
                    String prefix = "boba:high-frequency:" + UUID.randomUUID();
                    String[] keys = {prefix + ":h", prefix + ":l", prefix + ":s", prefix + ":z"};
                    try {
                        scenario.run(client, keys);
                        assertEquals("PONG", client.sync().ping());
                    } finally {
                        client.sync().del(keys);
                        await(client.binary().del(binary(keys[0]), binary(keys[1]), binary(keys[2]), binary(keys[3])));
                    }
                }
            }
        }
    }

    private static byte[] binary(String text) {
        byte[] prefix = ascii(text);
        byte[] result = Arrays.copyOf(prefix, prefix.length + 2);
        result[prefix.length] = (byte) 0xff;
        return result;
    }

    private static byte[] ascii(String text) {
        return text.getBytes(StandardCharsets.US_ASCII);
    }

    private static <T> T await(CompletionStage<T> stage) throws Exception {
        return stage.toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    private static void number(long expected, CompletionStage<Long> stage) throws Exception {
        assertEquals(Long.valueOf(expected), await(stage));
    }

    private static void serverError(CompletionStage<?> stage) {
        ExecutionException error = assertThrows(ExecutionException.class, () -> await(stage));
        assertTrue(error.getCause() instanceof BobaStrawServerException, error.toString());
    }

    private interface Scenario {
        void run(BobaStrawClient client, String[] keys) throws Exception;
    }
}
