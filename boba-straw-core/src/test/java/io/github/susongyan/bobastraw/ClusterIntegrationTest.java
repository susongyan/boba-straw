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

/** Mutates ONLY the disposable, labelled cluster created by scripts/cluster-test-up.sh. */
@EnabledIfSystemProperty(named = "boba.straw.runCluster", matches = "true")
class ClusterIntegrationTest {
    @Test
    void sameSlotCommandsAndMigrationAskWorkWithBothProtocols() throws Exception {
        assertTestContainer();
        for (ProtocolVersion protocol : new ProtocolVersion[] {ProtocolVersion.RESP2, ProtocolVersion.AUTO}) {
            String key = "boba-cluster:{" + UUID.randomUUID() + "}:a";
            String other = key + ":b";
            try (BobaStrawClusterClient client = cluster(protocol);
                 BobaStrawClient discovery = admin(17401)) {
                try {
                    assertEquals("OK", reply(client.executeAsync("MSET", key, "tea", other, "milk")).asString());
                    List<RespValue> values = array(reply(client.executeAsync("MGET", key, other)));
                    assertEquals("tea", values.get(0).asString());
                    assertEquals("milk", values.get(1).asString());
                    assertThrows(IllegalArgumentException.class, () -> client.executeAsync("MGET", "a", "b"));
                } finally {
                    reply(client.executeAsync("DEL", key, other));
                }

                int slot = ClusterSlot.of(key);
                List<RespValue> topology = array(reply(discovery.executeAsync("CLUSTER", "SLOTS")));
                int sourcePort = owner(topology, slot);
                int targetPort = differentPrimary(topology, sourcePort);
                try (BobaStrawClient source = admin(sourcePort); BobaStrawClient target = admin(targetPort)) {
                    String sourceId = reply(source.executeAsync("CLUSTER", "MYID")).asString();
                    String targetId = reply(target.executeAsync("CLUSTER", "MYID")).asString();
                    try {
                        reply(target.executeAsync("CLUSTER", "SETSLOT", String.valueOf(slot), "IMPORTING", sourceId));
                        reply(source.executeAsync("CLUSTER", "SETSLOT", String.valueOf(slot), "MIGRATING", targetId));
                        Throwable error = failure(source.executeAsync("GET", key));
                        assertTrue(error instanceof BobaStrawServerException);
                        assertTrue(error.getMessage().startsWith("ASK " + slot + " "));
                        assertNull(reply(client.executeAsync("GET", key)).asString());
                        assertNull(reply(client.executeAsync("GET", key)).asString());
                    } finally {
                        try {
                            reply(source.executeAsync("CLUSTER", "SETSLOT", String.valueOf(slot), "STABLE"));
                        } finally {
                            reply(target.executeAsync("CLUSTER", "SETSLOT", String.valueOf(slot), "STABLE"));
                        }
                    }
                }
            }
        }
    }

    @Test
    void periodicDiscoveryFollowsPlannedReplicaPromotion() throws Exception {
        assertTestContainer();
        String key = "boba-failover:" + UUID.randomUUID();
        try (BobaStrawClient discovery = admin(17401)) {
            List<RespValue> range = awaitReplicatedRange(discovery, ClusterSlot.of(key));
            int primary = port(range.get(2));
            assertTrue(range.size() >= 4, "Test cluster must have a replica");
            int replica = port(range.get(3));
            try (BobaStrawClient writer = admin(primary); BobaStrawClient promoted = admin(replica);
                 BobaStrawClusterClient client = cluster(ProtocolVersion.AUTO, primary)) {
                assertFalse(client.nodeMetrics().containsKey("[127.0.0.1]:" + replica));
                try {
                    reply(writer.executeAsync("SET", key, "retained"));
                    assertEquals(1, reply(writer.executeAsync("WAIT", "1", "2000")).asLong());
                    reply(promoted.executeAsync("CLUSTER", "FAILOVER"));
                    awaitOwner(discovery, ClusterSlot.of(key), replica);
                    awaitClientNode(client, replica);
                    assertEquals("retained", reply(client.executeAsync("GET", key)).asString());
                    assertTrue(client.topologyRefreshSuccesses() > 0);
                } finally {
                    reply(client.executeAsync("DEL", key));
                }
            }
        }
    }

    @Test
    void unavailablePrimaryFailsVisiblyThenBackgroundTopologyFindsPromotion() throws Exception {
        assertTestContainer();
        String key = "boba-outage:" + UUID.randomUUID();
        try (BobaStrawClient discovery = admin(17401)) {
            List<RespValue> range = awaitReplicatedRange(discovery, ClusterSlot.of(key));
            int primary = port(range.get(2));
            assertTrue(range.size() >= 4, "Test cluster must have a replica");
            int replica = port(range.get(3));
            try (BobaStrawClient writer = admin(primary); BobaStrawClient observer = admin(replica);
                 BobaStrawClusterClient client = cluster(ProtocolVersion.RESP2, primary)) {
                assertFalse(client.nodeMetrics().containsKey("[127.0.0.1]:" + replica));
                reply(writer.executeAsync("SET", key, "survives"));
                assertEquals(1, reply(writer.executeAsync("WAIT", "1", "2000")).asLong());
                // SIGSTOP makes both client traffic and Cluster heartbeat unavailable. Always resume.
                try {
                    signal(primary, "STOP");
                    Throwable error = failure(client.executeAsync("GET", key));
                    assertTrue(error instanceof BobaStrawConnectionException, error.toString());
                    awaitOwner(observer, ClusterSlot.of(key), replica);
                    awaitClientNode(client, replica);
                    assertEquals("survives", reply(client.executeAsync("GET", key)).asString());
                } finally {
                    signal(primary, "CONT");
                    awaitOwner(observer, ClusterSlot.of(key), replica);
                    client.refreshTopology().toCompletableFuture().get(5, TimeUnit.SECONDS);
                    reply(client.executeAsync("DEL", key));
                }
            }
        }
    }

    private static BobaStrawClusterClient cluster(ProtocolVersion protocol) {
        return cluster(protocol, 17401);
    }

    private static BobaStrawClusterClient cluster(ProtocolVersion protocol, int seed) {
        // Use one seed to also verify discovering and retiring non-seed primaries.
        return BobaStrawClusterClient.builder().seed("127.0.0.1", seed).protocol(protocol)
            .commandTimeout(Duration.ofSeconds(1)).topologyRefreshInterval(Duration.ofMillis(200))
            .reconnectInterval(Duration.ofMillis(50)).reconnectMaxInterval(Duration.ofMillis(200)).build();
    }

    private static BobaStrawClient admin(int port) {
        assertTrue(port >= 17401 && port <= 17406, "Refusing to access a non-test endpoint");
        return BobaStrawClient.builder().endpoint("127.0.0.1", port)
            .commandTimeout(Duration.ofSeconds(3)).build();
    }

    private static RespValue reply(CompletionStage<RespValue> result) throws Exception {
        return result.toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    private static Throwable failure(CompletionStage<RespValue> result) throws Exception {
        Throwable error = assertThrows(java.util.concurrent.ExecutionException.class, () -> reply(result));
        while (error instanceof java.util.concurrent.ExecutionException
            || error instanceof java.util.concurrent.CompletionException) {
            error = error.getCause();
        }
        return error;
    }

    private static List<RespValue> array(RespValue value) {
        return ((RespValue.Array) value).values;
    }

    private static int port(RespValue endpoint) {
        return (int) array(endpoint).get(1).asLong();
    }

    private static List<RespValue> range(List<RespValue> topology, int slot) {
        for (RespValue value : topology) {
            List<RespValue> range = array(value);
            if (range.get(0).asLong() <= slot && range.get(1).asLong() >= slot) {
                return range;
            }
        }
        throw new AssertionError("Missing slot " + slot);
    }

    private static int owner(List<RespValue> topology, int slot) {
        return port(range(topology, slot).get(2));
    }

    private static int differentPrimary(List<RespValue> topology, int source) {
        for (RespValue value : topology) {
            int candidate = port(array(value).get(2));
            if (candidate != source) {
                return candidate;
            }
        }
        throw new AssertionError("Expected multiple primaries");
    }

    private static void awaitOwner(BobaStrawClient observer, int slot, int expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            if (owner(array(reply(observer.executeAsync("CLUSTER", "SLOTS"))), slot) == expected) {
                return;
            }
            Thread.sleep(50);
        }
        fail("Replica was not promoted");
    }

    private static List<RespValue> awaitReplicatedRange(BobaStrawClient observer, int slot) throws Exception {
        // A preceding test may have promoted a replica; wait until the old primary has rejoined.
        // This is a fixture readiness condition, not a retry of an application command.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            List<RespValue> range = range(array(reply(observer.executeAsync("CLUSTER", "SLOTS"))), slot);
            if (range.size() >= 4) {
                return range;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Test slot has no healthy replica after cluster convergence");
    }

    private static void awaitClientNode(BobaStrawClusterClient client, int port) throws Exception {
        String endpoint = "[127.0.0.1]:" + port;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            BobaStrawClientMetrics metrics = client.nodeMetrics().get(endpoint);
            if (metrics != null && metrics.sharedConnectionState() == BobaStrawConnectionState.READY) {
                return;
            }
            Thread.sleep(50);
        }
        fail("Client did not discover the promoted replica");
    }

    private static void assertTestContainer() throws Exception {
        Process process = new ProcessBuilder("docker", "inspect", "--format",
            "{{index .Config.Labels \"io.github.susongyan.boba-test\"}}", "boba-straw-cluster-test")
            .redirectErrorStream(true).start();
        assertTrue(process.waitFor(5, TimeUnit.SECONDS));
        byte[] output = new byte[64];
        int count = process.getInputStream().read(output);
        assertEquals(0, process.exitValue());
        assertEquals("cluster", new String(output, 0, count, java.nio.charset.StandardCharsets.UTF_8).trim());
    }

    private static void signal(int port, String signal) throws Exception {
        assertTrue(port >= 17401 && port <= 17406);
        assertTrue("STOP".equals(signal) || "CONT".equals(signal));
        String script = "test_pid=$(cat /data/node-" + port + "/redis.pid); ";
        if ("STOP".equals(signal)) {
            // Independent safety net also runs if Maven/JVM is interrupted before its finally block.
            script += "(sleep 30; kill -CONT \"$test_pid\") >/dev/null 2>&1 & ";
        }
        script += "kill -" + signal + " \"$test_pid\"";
        Process process = new ProcessBuilder("docker", "exec", "boba-straw-cluster-test", "sh", "-c", script)
            .inheritIO().start();
        boolean finished = process.waitFor(5, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
        }
        assertTrue(finished, "Docker fault control timed out; container watchdog will resume the node");
        assertEquals(0, process.exitValue());
    }
}
