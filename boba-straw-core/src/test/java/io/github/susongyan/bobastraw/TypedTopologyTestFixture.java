package io.github.susongyan.bobastraw;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/** Shared behavior contract; each run owns only its UUID-prefixed, same-slot keys. */
final class TypedTopologyTestFixture {
    private TypedTopologyTestFixture() {
    }

    static void verify(BobaStrawAsyncCommands c) throws Exception {
        String prefix = "boba-typed:{" + UUID.randomUUID() + "}:";
        String k = prefix + "string";
        String h = prefix + "hash";
        String l = prefix + "list";
        String s = prefix + "set";
        String z = prefix + "zset";
        String missing = prefix + "missing";
        try {
            assertNull(await(c.get(k)));
            assertEquals("OK", await(c.set(k, "茶")));
            assertEquals("茶", await(c.get(k)));
            assertEquals(Arrays.asList("茶", null), await(c.mget(k, missing)));
            Map<String, String> pairs = new LinkedHashMap<String, String>();
            pairs.put(k, "1");
            pairs.put(missing, "2");
            assertEquals("OK", await(c.mset(pairs)));
            assertEquals(Long.valueOf(2), await(c.existsCount(k, missing)));
            assertEquals(Long.valueOf(2), await(c.incr(k)));
            assertEquals(Long.valueOf(1), await(c.expire(k, 60)));
            assertTrue(await(c.ttl(k)) > 0);
            assertEquals(Long.valueOf(1), await(c.persist(k)));
            assertEquals(Long.valueOf(1), await(c.hset(h, "field", "茶")));
            assertEquals(Collections.singletonMap("field", "茶"), await(c.hgetall(h)));
            assertEquals(Arrays.asList("茶", null), await(c.hmget(h, "field", "absent")));
            assertTrue(await(c.hexists(h, "field")));
            assertEquals(Long.valueOf(2), await(c.rpush(l, "a", "b")));
            assertEquals(Arrays.asList("a", "b"), await(c.lrange(l, 0, -1)));
            assertEquals("a", await(c.lpop(l)));
            assertEquals(Long.valueOf(1), await(c.sadd(s, "a", "a")));
            assertEquals(Collections.singleton("a"), await(c.smembers(s)));
            assertTrue(await(c.sismember(s, "a")));
            assertEquals(Long.valueOf(1), await(c.zadd(z, 1.5, "a")));
            assertEquals(Double.valueOf(1.5), await(c.zscore(z, "a")));
            assertEquals(Long.valueOf(0), await(c.zrank(z, "a")));
            assertNull(await(c.zscore(z, "absent")));
            assertEquals(Collections.singletonList("a"), await(c.zrange(z, 0, -1)));
            ExecutionException wrongType = assertThrows(ExecutionException.class, () -> await(c.hlen(k)));
            assertTrue(wrongType.getCause() instanceof BobaStrawServerException);
            assertEquals("2", await(c.get(k)), "Mapping/error handling must not shift the next response");
            await(c.rpush(l, "right"));
            assertEquals(Arrays.asList(l, "b"), await(c.blpop(1, l)));
            assertEquals(Arrays.asList(l, "right"), await(c.brpop(1, l)));
        } finally {
            await(c.del(k, h, l, s, z, missing));
        }
    }

    static <T> T await(CompletionStage<T> stage) throws Exception {
        return stage.toCompletableFuture().get(10, TimeUnit.SECONDS);
    }
}
