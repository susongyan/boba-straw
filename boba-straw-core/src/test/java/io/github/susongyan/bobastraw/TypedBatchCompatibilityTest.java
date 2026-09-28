package io.github.susongyan.bobastraw;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "boba.straw.runCompatibility", matches = "true")
class TypedBatchCompatibilityTest {
    @Test
    void mixedTypedAndRawBatchesRetainPositionErrorsAndTypes() throws Exception {
        matrix(client -> {
            BobaStrawPipeline pipeline = client.pipeline().command("PING");
            verify(client, pipeline.typed(), pipeline::executeTyped);
            assertThrows(IllegalStateException.class, pipeline::execute);
            assertThrows(IllegalStateException.class, () -> pipeline.typed().get("unused"));
            try (BobaStrawTransaction transaction = client.transaction()) {
                transaction.command("PING");
                verify(client, transaction.typed(), transaction::execTyped);
                assertThrows(IllegalStateException.class, transaction::exec);
                assertThrows(IllegalStateException.class, () -> transaction.typed().get("unused"));
            }
            assertTrue(await(client.pipeline().executeTyped()).replies().isEmpty());
            try (BobaStrawTransaction transaction = client.transaction()) {
                BobaStrawBatchResult empty = await(transaction.execTyped());
                assertFalse(empty.isAborted());
                assertTrue(empty.replies().isEmpty());
            }
        });
    }

    @Test
    void watchAbortQueueErrorAndLegacyPipelineFailureStayDistinct() throws Exception {
        matrix(client -> {
            String key = "boba-typed-watch:" + UUID.randomUUID();
            try {
                client.sync().set(key, "before");
                try (BobaStrawTransaction transaction = client.transaction()) {
                    await(transaction.watch(key));
                    BobaStrawCommandHandle<String> write = transaction.typed().set(key, "wrong");
                    client.sync().set(key, "changed");
                    BobaStrawBatchResult result = await(transaction.execTyped());
                    assertTrue(result.isAborted());
                    assertThrows(IllegalStateException.class, () -> result.get(write));
                    assertEquals("changed", client.sync().get(key));
                }
                try (BobaStrawTransaction transaction = client.transaction()) {
                    transaction.typed().set(key, "must-not-execute");
                    transaction.command("SET", key);
                    assertThrows(ExecutionException.class, () -> await(transaction.execTyped()));
                    assertEquals("changed", client.sync().get(key));
                }
                assertThrows(ExecutionException.class,
                    () -> await(client.pipeline().command("INCR", key).execute()));
                try (BobaStrawTransaction transaction = client.transaction()) {
                    BobaStrawCommandHandle<String> value = transaction.typed().get(key);
                    assertEquals("changed", await(transaction.execTyped()).get(value));
                }
            } finally {
                client.sync().del(key);
            }
        });
    }

    private static void verify(BobaStrawClient client, BobaStrawBatchCommands c,
                               Supplier<CompletionStage<BobaStrawBatchResult>> execute) throws Exception {
        String prefix = "boba-typed-batch:" + UUID.randomUUID();
        String k = prefix + ":k", h = prefix + ":h", l = prefix + ":l";
        String s = prefix + ":s", z = prefix + ":z", missing = prefix + ":missing";
        try {
            BobaStrawCommandHandle<String> set = c.set(k, "茶");
            BobaStrawCommandHandle<String> get = c.get(k);
            BobaStrawCommandHandle<Boolean> exists = c.exists(k);
            BobaStrawCommandHandle<Long> ttl = c.ttl(k);
            BobaStrawCommandHandle<Long> error = c.incr(k);
            BobaStrawCommandHandle<List<String>> mget = c.mget(k, missing);
            BobaStrawCommandHandle<Long> hs = c.hset(h, "f", "v");
            BobaStrawCommandHandle<String> hg = c.hget(h, "f");
            BobaStrawCommandHandle<Map<String, String>> hm = c.hgetall(h);
            BobaStrawCommandHandle<Long> lp = c.lpush(l, "a", "b");
            BobaStrawCommandHandle<List<String>> lr = c.lrange(l, 0, -1);
            BobaStrawCommandHandle<Long> sa = c.sadd(s, "a", "a");
            BobaStrawCommandHandle<Set<String>> sm = c.smembers(s);
            BobaStrawCommandHandle<Long> za = c.zadd(z, 1.5, "a");
            BobaStrawCommandHandle<Double> zs = c.zscore(z, "a");
            BobaStrawCommandHandle<Double> absent = c.zscore(z, "absent");
            BobaStrawCommandHandle<Long> del = c.del(k);
            BobaStrawBatchResult r = await(execute.get());
            assertFalse(r.isAborted());
            assertEquals("PONG", r.replies().get(0).asString());
            assertEquals("OK", r.get(set));
            assertEquals("茶", r.get(get));
            assertTrue(r.get(exists));
            assertEquals(Long.valueOf(-1), r.get(ttl));
            assertThrows(BobaStrawServerException.class, () -> r.get(error));
            assertEquals(Arrays.asList("茶", null), r.get(mget));
            assertEquals(Long.valueOf(1), r.get(hs));
            assertEquals("v", r.get(hg));
            assertEquals(Collections.singletonMap("f", "v"), r.get(hm));
            assertEquals(Long.valueOf(2), r.get(lp));
            assertEquals(Arrays.asList("b", "a"), r.get(lr));
            assertEquals(Long.valueOf(1), r.get(sa));
            assertEquals(Collections.singleton("a"), r.get(sm));
            assertEquals(Long.valueOf(1), r.get(za));
            assertEquals(Double.valueOf(1.5), r.get(zs));
            assertNull(r.get(absent));
            assertEquals(Long.valueOf(1), r.get(del));
            assertNull(client.sync().get(k));
        } finally {
            client.sync().del(k, h, l, s, z, missing);
        }
    }

    private static <T> T await(CompletionStage<T> stage) throws Exception {
        return stage.toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    private static void matrix(Check check) throws Exception {
        for (int port : new int[] {16379, 16380, 16381, 16382}) {
            for (ProtocolVersion protocol : new ProtocolVersion[] {ProtocolVersion.RESP2, ProtocolVersion.AUTO}) {
                try (BobaStrawClient client = BobaStrawClient.builder().endpoint("127.0.0.1", port)
                    .protocol(protocol).commandTimeout(Duration.ofSeconds(3)).build()) {
                    check.run(client);
                }
            }
        }
    }

    private interface Check {
        void run(BobaStrawClient client) throws Exception;
    }
}
