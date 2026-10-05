package io.github.susongyan.bobastraw;

import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Shared real-server acceptance, with isolated keys and no whole-database cleanup. */
final class TopologyDedicatedTestFixture {
    private TopologyDedicatedTestFixture() { }

    static void verify(BobaStrawAsyncCommands commands, BobaStrawSyncCommands sync,
                       BobaStrawScripts scripts, BobaStrawPubSub pubSub,
                       java.util.function.Function<String, BobaStrawTransaction> transactions,
                       boolean cluster, CommandExecutor raw) throws Exception {
        String key = "{boba-c6-" + UUID.randomUUID() + "}:value";
        String list = key + ":list";
        String channel = key + ":events";
        scripts.register("c6-increment", "return redis.call('INCR', KEYS[1])", ScriptOutput.integer());
        try {
            try (BobaStrawTransaction tx = transactions.apply(key)) {
                TypedTopologyTestFixture.await(tx.watch(key));
                if (cluster) {
                    assertThrows(IllegalArgumentException.class, () -> tx.command("SET", "{other}:key", "x"));
                    assertThrows(IllegalArgumentException.class, () -> tx.watch("{other}:key"));
                }
                BobaStrawCommandHandle<Long> increment = tx.typed().script(
                    "c6-increment", ScriptOutput.integer(), new String[] {key});
                BobaStrawBatchResult result = TypedTopologyTestFixture.await(tx.execTyped());
                assertFalse(result.isAborted());
                assertEquals(Long.valueOf(1), result.get(increment));
            }
            try (BobaStrawTransaction tx = transactions.apply(key)) {
                TypedTopologyTestFixture.await(tx.watch(key));
                TypedTopologyTestFixture.await(commands.set(key, "2"));
                tx.typed().incr(key);
                assertTrue(TypedTopologyTestFixture.await(tx.execTyped()).isAborted());
            }
            TypedTopologyTestFixture.await(commands.rpush(list, "tea"));
            assertEquals(Arrays.asList(list, "tea"), sync.blpop(1, list));
            CompletableFuture<java.util.List<String>> blocked = commands.brpop(0, list).toCompletableFuture();
            assertTrue(blocked.cancel(false));
            assertTrue(blocked.isCancelled());
            assertEquals("2", sync.get(key));
            if (cluster) {
                assertThrows(IllegalArgumentException.class, () -> commands.blpop(1, list, "{other}:list"));
            }
            CompletableFuture<String> message = new CompletableFuture<String>();
            BobaStrawSubscription subscription = TypedTopologyTestFixture.await(pubSub.subscribe(channel, message::complete));
            assertTrue(subscription.termination().toCompletableFuture().cancel(false));
            CompletableFuture<Void> ended = subscription.termination().toCompletableFuture();
            try {
                TypedTopologyTestFixture.await(raw.executeAsync("PUBLISH", channel, "bubble"));
                assertEquals("bubble", message.get(3, TimeUnit.SECONDS));
            } finally {
                subscription.close();
            }
            ended.get(3, TimeUnit.SECONDS);
            CompletableFuture<String> patternMessage = new CompletableFuture<String>();
            subscription = TypedTopologyTestFixture.await(pubSub.psubscribe(channel + "*", patternMessage::complete));
            ended = subscription.termination().toCompletableFuture();
            try {
                TypedTopologyTestFixture.await(raw.executeAsync("PUBLISH", channel + ":pattern", "milk"));
                assertEquals("milk", patternMessage.get(3, TimeUnit.SECONDS));
            } finally {
                subscription.close();
            }
            ended.get(3, TimeUnit.SECONDS);
        } finally {
            TypedTopologyTestFixture.await(commands.del(key, list));
        }
    }
}
