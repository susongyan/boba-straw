package io.github.susongyan.bobastraw;

import io.github.susongyan.bobastraw.protocol.RespValue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Mutates only the labelled, disposable sentinel-test fixture. Never run in parallel. */
@EnabledIfSystemProperty(named = "boba.straw.runSentinel", matches = "true")
class SentinelIntegrationTest {
    @Test
    void scanPagesUseDiscoveredPrimary() throws Exception {
        assertTestContainer();
        for (ProtocolVersion protocol : new ProtocolVersion[] {ProtocolVersion.RESP2, ProtocolVersion.AUTO}) {
            try (BobaStrawSentinelClient client = builder(protocol).build()) {
                ScanCompatibilityTest.verify(client.async(), client.scan(), true);
            }
        }
    }

    @Test
    void typedOrdinaryCommandsUseDiscoveredPrimary() throws Exception {
        assertTestContainer();
        for (ProtocolVersion protocol : new ProtocolVersion[] {ProtocolVersion.RESP2, ProtocolVersion.AUTO}) {
            try (BobaStrawSentinelClient client = builder(protocol).build()) {
                TypedTopologyTestFixture.verify(client.async());
            }
        }
    }

    @Test
    void authenticatedFailoverPreservesDataAndFindsNewPrimaryWithBothProtocols() throws Exception {
        assertTestContainer();
        for (ProtocolVersion protocol : new ProtocolVersion[] {ProtocolVersion.RESP2, ProtocolVersion.AUTO}) {
            try (BobaStrawClient sentinel = admin(27501, "boba-test-sentinel");
                 BobaStrawSentinelClient client = builder(protocol).build()) {
                int original = masterPort(sentinel);
                int replacement = original == 17501 ? 17502 : 17501;
                String key = "boba-sentinel:" + UUID.randomUUID();
                try (BobaStrawClient writer = admin(original, "boba-test-data");
                     BobaStrawClient replica = admin(replacement, "boba-test-data")) {
                    awaitReplica(replica);
                    ReplicationTestFixture.writeAndAwaitReplica(writer, key, "tea", "5000");
                    try {
                        assertEquals("tea", TypedTopologyTestFixture.await(client.async().get(key)));
                        assertEquals("OK", reply(sentinel.executeAsync("SENTINEL", "FAILOVER", "tea")).asString());
                        awaitMaster(client, sentinel, replacement);
                        assertEquals("tea", TypedTopologyTestFixture.await(client.async().get(key)));
                        assertEquals("OK", TypedTopologyTestFixture.await(client.async().set(key, "new-primary")));
                        assertEquals("new-primary", TypedTopologyTestFixture.await(client.async().get(key)));
                    } finally {
                        // A real switch disconnects old normal clients. Resolve again for cleanup.
                        awaitMaster(client, sentinel, masterPort(sentinel));
                        reply(client.executeAsync("DEL", key));
                    }
                }
            }
        }
    }

    @Test
    void wrongPasswordsRemainAuthenticationFailuresInAutoAndResp2() throws Exception {
        assertTestContainer();
        for (ProtocolVersion protocol : new ProtocolVersion[] {ProtocolVersion.RESP2, ProtocolVersion.AUTO}) {
            BobaStrawConnectionException sentinelError = assertThrows(BobaStrawConnectionException.class,
                () -> builder(protocol).sentinelCredentials(null, "incorrect").build());
            assertTrue(causes(sentinelError).contains("WRONGPASS"), causes(sentinelError));
            BobaStrawConnectionException dataError = assertThrows(BobaStrawConnectionException.class,
                () -> builder(protocol).credentials(null, "incorrect").build());
            assertTrue(causes(dataError).contains("WRONGPASS"), causes(dataError));
        }
    }

    private static BobaStrawSentinelClient.Builder builder(ProtocolVersion protocol) {
        return BobaStrawSentinelClient.builder().masterName("tea")
            .sentinel("127.0.0.1", 27501).sentinel("127.0.0.1", 27502).sentinel("127.0.0.1", 27503)
            .sentinelCredentials(null, "boba-test-sentinel").credentials(null, "boba-test-data")
            .protocol(protocol).commandTimeout(Duration.ofSeconds(2))
            .topologyRefreshInterval(Duration.ofMillis(100))
            .reconnectInterval(Duration.ofMillis(50)).reconnectMaxInterval(Duration.ofMillis(200));
    }

    private static BobaStrawClient admin(int port, String password) {
        assertTrue(port == 17501 || port == 17502 || port == 27501);
        return BobaStrawClient.builder().endpoint("127.0.0.1", port).credentials(null, password)
            .commandTimeout(Duration.ofSeconds(6)).build();
    }

    private static int masterPort(BobaStrawClient sentinel) throws Exception {
        List<RespValue> values = ((RespValue.Array) reply(
            sentinel.executeAsync("SENTINEL", "get-master-addr-by-name", "tea"))).values;
        int port = Integer.parseInt(values.get(1).asString());
        assertTrue(port == 17501 || port == 17502);
        return port;
    }

    private static void awaitReplica(BobaStrawClient replica) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            try {
                List<RespValue> role = ((RespValue.Array) reply(replica.executeAsync("ROLE"))).values;
                if ("slave".equals(role.get(0).asString()) && "connected".equals(role.get(3).asString())) {
                    return;
                }
            } catch (java.util.concurrent.ExecutionException transientDisconnect) {
                // Fixture convergence after the preceding test's failover. No application replay.
            }
            Thread.sleep(50);
        }
        fail("Fixture replica has not rejoined");
    }

    private static void awaitMaster(BobaStrawSentinelClient client, BobaStrawClient sentinel, int port)
        throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            if (masterPort(sentinel) == port && ("[127.0.0.1]:" + port).equals(client.masterAddress())
                && client.connectionState() == BobaStrawConnectionState.READY) {
                return;
            }
            Thread.sleep(50);
        }
        fail("Sentinel client did not follow the promoted primary");
    }

    private static RespValue reply(CompletionStage<RespValue> result) throws Exception {
        return result.toCompletableFuture().get(8, TimeUnit.SECONDS);
    }

    private static String causes(Throwable error) {
        StringBuilder result = new StringBuilder();
        for (Throwable current = error; current != null; current = current.getCause()) {
            result.append(current.getMessage()).append('\n');
        }
        return result.toString();
    }

    private static void assertTestContainer() throws Exception {
        Process process = new ProcessBuilder("docker", "inspect", "--format",
            "{{index .Config.Labels \"io.github.susongyan.boba-test\"}}", "boba-straw-sentinel-test")
            .redirectErrorStream(true).start();
        assertTrue(process.waitFor(5, TimeUnit.SECONDS));
        byte[] output = new byte[64];
        int count = process.getInputStream().read(output);
        assertEquals(0, process.exitValue());
        assertEquals("sentinel", new String(output, 0, count, java.nio.charset.StandardCharsets.UTF_8).trim());
    }
}
