package io.github.susongyan.bobastraw;

import io.github.susongyan.bobastraw.protocol.RespValue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Real Redis 5/6.2/7.4 and Valkey 8.1 tests, isolated by per-run keys. */
@EnabledIfSystemProperty(named = "boba.straw.runCompatibility", matches = "true")
class DedicatedConnectionCompatibilityTest {
    @Test
    void watchConflictDiscardAndExecErrorsDoNotPolluteTheNextLease() throws Exception {
        for (int port : new int[] {16379, 16380, 16381, 16382}) {
            for (ProtocolVersion protocol : new ProtocolVersion[] {ProtocolVersion.AUTO, ProtocolVersion.RESP2}) {
                String key = "boba-straw:dedicated:" + UUID.randomUUID();
                try (BobaStrawClient client = client(port, protocol)) {
                    try {
                        client.sync().set(key, "one");
                        try (BobaStrawTransaction transaction = client.transaction()) {
                            transaction.watch(key).toCompletableFuture().get(2, TimeUnit.SECONDS);
                            client.sync().set(key, "changed");
                            assertTrue(transaction.command("SET", key, "wrong").exec()
                                .toCompletableFuture().get(2, TimeUnit.SECONDS).isEmpty());
                            assertEquals("changed", client.sync().get(key));
                        }
                        try (BobaStrawTransaction transaction = client.transaction()) {
                            transaction.watch(key).toCompletableFuture().get(2, TimeUnit.SECONDS);
                            transaction.command("SET", key, "discarded");
                            assertEquals("OK", transaction.discard().toCompletableFuture()
                                .get(2, TimeUnit.SECONDS).asString());
                            assertEquals("changed", client.sync().get(key));
                        }
                        client.sync().set(key, "after-discard");
                        try (BobaStrawTransaction transaction = client.transaction()) {
                            List<RespValue> values = transaction.command("LPUSH", key, "wrong-type")
                                .command("SET", key, "after-error").exec()
                                .toCompletableFuture().get(2, TimeUnit.SECONDS);
                            assertTrue(values.get(0) instanceof RespValue.Error);
                            assertEquals("OK", values.get(1).asString());
                            assertEquals("after-error", client.sync().get(key));
                        }
                        try (BobaStrawTransaction transaction = client.transaction()) {
                            assertThrows(java.util.concurrent.ExecutionException.class,
                                () -> transaction.command("SET", key).exec().toCompletableFuture()
                                    .get(2, TimeUnit.SECONDS));
                        }
                        try (BobaStrawTransaction transaction = client.transaction()) {
                            assertEquals("after-error", transaction.command("GET", key).exec()
                                .toCompletableFuture().get(2, TimeUnit.SECONDS).get(0).asString());
                        }
                    } finally {
                        client.sync().del(key);
                    }
                }
            }
        }
    }

    @Test
    void blockingPopsHaveIndependentConnectionsAndServerTimeoutIsNotAnError() throws Exception {
        for (int port : new int[] {16379, 16380, 16381, 16382}) {
            for (ProtocolVersion protocol : new ProtocolVersion[] {ProtocolVersion.AUTO, ProtocolVersion.RESP2}) {
                String key = "boba-straw:blocking:" + UUID.randomUUID();
                try (BobaStrawClient client = client(port, protocol)) {
                    try {
                        CompletableFuture<List<String>> pop = client.async().blpop(0, key).toCompletableFuture();
                        assertEquals("PONG", client.sync().ping());
                        client.sync().rpush(key, "tea");
                        assertEquals(Arrays.asList(key, "tea"), pop.get(2, TimeUnit.SECONDS));
                        client.sync().rpush(key, "first", "last");
                        assertEquals(Arrays.asList(key, "last"), client.sync().brpop(1, key));
                        assertEquals(Arrays.asList(key, "first"), client.sync().blpop(1, key));
                        assertTrue(client.async().blpop(1, key).toCompletableFuture()
                            .get(3, TimeUnit.SECONDS).isEmpty());
                        CompletableFuture<List<String>> cancelled = client.async().blpop(0, key).toCompletableFuture();
                        assertTrue(cancelled.cancel(false));
                        assertEquals("PONG", client.sync().ping());
                    } finally {
                        client.sync().del(key);
                    }
                }
            }
        }
    }

    private static BobaStrawClient client(int port, ProtocolVersion protocol) {
        return BobaStrawClient.builder().endpoint("127.0.0.1", port).protocol(protocol)
            .commandTimeout(Duration.ofSeconds(5)).transactionPoolMaxSize(1).build();
    }
}
