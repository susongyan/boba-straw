package io.github.susongyan.bobastraw;

import io.github.susongyan.bobastraw.protocol.RespValue;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import static org.junit.jupiter.api.Assertions.*;

/** Mutates only the four labelled disposable C7 fixtures. Run sequentially. */
@EnabledIfSystemProperty(named = "boba.straw.runTls", matches = "true")
class TlsCompatibilityTest {
    private static BobaStrawTlsOptions tls;
    private static BobaStrawTlsOptions trustOnly;

    @BeforeAll
    static void setup() throws Exception {
        for (String name : new String[] {"boba-straw-tls-62", "boba-straw-tls-74",
            "boba-straw-tls-valkey", "boba-straw-tls-topology"}) {
            Process process = new ProcessBuilder("docker", "inspect", "--format",
                "{{index .Config.Labels \"io.github.susongyan.boba-test\"}}", name).start();
            assertTrue(process.waitFor(5, TimeUnit.SECONDS), "Docker inspection timed out");
            byte[] response = new byte[128];
            int length = process.getInputStream().read(response);
            assertEquals(0, process.exitValue());
            assertEquals("tls", new String(response, 0, length, "UTF-8").trim());
        }
        String location = System.getenv("BOBA_TLS_CERT_DIR");
        assertNotNull(location, "Set BOBA_TLS_CERT_DIR to the directory produced by tls-test-up.sh");
        Path directory = Paths.get(location);
        KeyStore trust = KeyStore.getInstance("JKS");
        trust.load(null, null);
        try (InputStream input = Files.newInputStream(directory.resolve("ca.crt"))) {
            trust.setCertificateEntry("ca", CertificateFactory.getInstance("X.509").generateCertificate(input));
        }
        TrustManagerFactory tm = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tm.init(trust);
        KeyStore identity = KeyStore.getInstance("PKCS12");
        try (InputStream input = Files.newInputStream(directory.resolve("client.p12"))) {
            identity.load(input, "test-only".toCharArray());
        }
        KeyManagerFactory km = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        km.init(identity, "test-only".toCharArray());
        SSLContext authenticated = SSLContext.getInstance("TLS");
        authenticated.init(km.getKeyManagers(), tm.getTrustManagers(), null);
        SSLContext anonymous = SSLContext.getInstance("TLS");
        anonymous.init(null, tm.getTrustManagers(), null);
        tls = BobaStrawTlsOptions.builder().sslContext(authenticated).build();
        trustOnly = BobaStrawTlsOptions.builder().sslContext(anonymous).build();
    }

    @Test
    void standaloneRedisAndValkeyCoverBothRespVersionsAndDedicatedCapabilities() throws Exception {
        for (int port : new int[] {17679, 17680, 17681}) {
            for (ProtocolVersion protocol : new ProtocolVersion[] {ProtocolVersion.RESP2, ProtocolVersion.AUTO}) {
                try (BobaStrawClient client = client(port, protocol, tls, "boba-tls-test")) {
                    assertEquals("PONG", client.sync().ping());
                    String key = "boba-c7:" + UUID.randomUUID();
                    byte[] bytes = new byte[131072];
                    Arrays.fill(bytes, (byte) 0xff);
                    try {
                        await(client.binary().set(key.getBytes("UTF-8"), bytes));
                        assertArrayEquals(bytes, await(client.binary().get(key.getBytes("UTF-8"))));
                        List<RespValue> values = await(client.pipeline().command("GET", key)
                            .command("PING").execute());
                        assertArrayEquals(bytes, ((RespValue.BlobString) values.get(0)).value);
                        assertEquals("PONG", values.get(1).asString());
                    } finally {
                        client.sync().del(key);
                    }
                    TopologyDedicatedTestFixture.verify(client.async(), client.sync(), client.scripts(),
                        client.pubSub(), ignored -> client.transaction(), false, client::executeAsync);
                }
            }
        }
    }

    @Test
    void clusterDedicatedConnectionsAskAndMovedStayEncrypted() throws Exception {
        for (ProtocolVersion protocol : new ProtocolVersion[] {ProtocolVersion.RESP2, ProtocolVersion.AUTO}) {
            try (BobaStrawClusterClient cluster = BobaStrawClusterClient.builder()
                .seed("127.0.0.1", 17601).seed("127.0.0.1", 17602).tls(tls)
                .protocol(protocol).commandTimeout(Duration.ofSeconds(5)).build();
                 BobaStrawClient discovery = admin(17601)) {
                TopologyDedicatedTestFixture.verify(cluster.async(), cluster.sync(), cluster.scripts(),
                    cluster.pubSub(), cluster::transaction, true,
                    (command, arguments) -> cluster.executeWithKeysAsync(new String[0], command, arguments));
                String key = "{c7-" + UUID.randomUUID() + "}";
                int slot = ClusterSlot.of(key);
                int sourcePort = 0;
                for (RespValue value : array(await(discovery.executeAsync("CLUSTER", "SLOTS")))) {
                    List<RespValue> range = array(value);
                    if (range.get(0).asLong() <= slot && range.get(1).asLong() >= slot) {
                        sourcePort = (int) array(range.get(2)).get(1).asLong();
                    }
                }
                assertTrue(sourcePort >= 17601 && sourcePort <= 17603);
                int targetPort = sourcePort == 17601 ? 17602 : 17601;
                try (BobaStrawClient source = admin(sourcePort); BobaStrawClient target = admin(targetPort)) {
                    String sourceId = await(source.executeAsync("CLUSTER", "MYID")).asString();
                    String targetId = await(target.executeAsync("CLUSTER", "MYID")).asString();
                    try {
                        await(target.executeAsync("CLUSTER", "SETSLOT", "" + slot, "IMPORTING", sourceId));
                        await(source.executeAsync("CLUSTER", "SETSLOT", "" + slot, "MIGRATING", targetId));
                        assertTrue(failure(source.executeAsync("GET", key)).getMessage().startsWith("ASK "));
                        assertNull(await(cluster.async().get(key)));
                    } finally {
                        await(source.executeAsync("CLUSTER", "SETSLOT", "" + slot, "STABLE"));
                        await(target.executeAsync("CLUSTER", "SETSLOT", "" + slot, "STABLE"));
                    }
                    try {
                        assignSlot(slot, targetId);
                        assertTrue(failure(source.executeAsync("GET", key)).getMessage().startsWith("MOVED "));
                        assertNull(await(cluster.async().get(key)));
                        await(cluster.refreshTopology());
                    } finally {
                        assignSlot(slot, sourceId);
                    }
                }
            }
        }
    }

    @Test
    void sentinelTlsFailoverRetiresOldLeasesAndUsesNewPrimary() throws Exception {
        try (BobaStrawSentinelClient client = sentinel(tls, tls);
             BobaStrawClient control = admin(27701)) {
            TopologyDedicatedTestFixture.verify(client.async(), client.sync(), client.scripts(),
                client.pubSub(), ignored -> client.transaction(), false, client::executeAsync);
            String previous = client.masterAddress();
            try (BobaStrawTransaction tx = client.transaction();
                 BobaStrawSubscription subscription = await(client.pubSub().subscribe("c7-failover", value -> { }))) {
                await(tx.watch("c7-watch:" + UUID.randomUUID()));
                await(control.executeAsync("SENTINEL", "FAILOVER", "tea"));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
                while (previous.equals(client.masterAddress()) && System.nanoTime() < deadline) {
                    await(client.refreshTopology());
                    Thread.sleep(100);
                }
                assertNotEquals(previous, client.masterAddress());
                assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> subscription.termination().toCompletableFuture().get(5, TimeUnit.SECONDS));
                assertThrows(BobaStrawCommandNotSentException.class, tx::exec);
            }
            assertEquals("PONG", client.sync().ping());
            TopologyDedicatedTestFixture.verify(client.async(), client.sync(), client.scripts(),
                client.pubSub(), ignored -> client.transaction(), false, client::executeAsync);
        }
    }

    @Test
    void authenticationAndIndependentTopologyTlsPoliciesFailClosed() throws Exception {
        try (BobaStrawClient missing = client(17680, ProtocolVersion.AUTO, trustOnly, "boba-tls-test")) {
            assertThrows(BobaStrawCommandNotSentException.class, () -> missing.sync().ping());
        }
        try (BobaStrawClient wrongPassword = client(17680, ProtocolVersion.AUTO, tls, "incorrect")) {
            assertThrows(BobaStrawConnectionException.class, () -> wrongPassword.sync().ping());
        }
        assertThrows(BobaStrawConnectionException.class, () -> sentinel(trustOnly, tls));
        assertThrows(BobaStrawConnectionException.class, () -> sentinel(tls, trustOnly));
        assertThrows(BobaStrawConnectionException.class, () -> BobaStrawClusterClient.builder()
            .seed("127.0.0.1", 17601).tls(trustOnly).commandTimeout(Duration.ofSeconds(2)).build());
    }

    private static BobaStrawSentinelClient sentinel(BobaStrawTlsOptions control, BobaStrawTlsOptions data) {
        return BobaStrawSentinelClient.builder().sentinel("127.0.0.1", 27701).masterName("tea")
            .sentinelTls(control).tls(data).discoveryTimeout(Duration.ofSeconds(3))
            .commandTimeout(Duration.ofSeconds(5)).build();
    }

    private static BobaStrawClient client(int port, ProtocolVersion protocol,
        BobaStrawTlsOptions options, String password) {
        return BobaStrawClient.builder().endpoint("127.0.0.1", port).tls(options).protocol(protocol)
            .credentials(null, password).commandTimeout(Duration.ofSeconds(5)).build();
    }

    private static BobaStrawClient admin(int port) {
        assertTrue((port >= 17601 && port <= 17603) || port == 27701);
        return client(port, ProtocolVersion.AUTO, tls, null);
    }

    private static void assignSlot(int slot, String owner) throws Exception {
        for (int port = 17601; port <= 17603; port++) {
            try (BobaStrawClient node = admin(port)) {
                await(node.executeAsync("CLUSTER", "SETSLOT", "" + slot, "NODE", owner));
            }
        }
    }

    private static List<RespValue> array(RespValue value) {
        return ((RespValue.Array) value).values;
    }

    private static <T> T await(CompletionStage<T> stage) throws Exception {
        return stage.toCompletableFuture().get(8, TimeUnit.SECONDS);
    }

    private static Throwable failure(CompletionStage<?> stage) {
        return assertThrows(java.util.concurrent.ExecutionException.class, () -> await(stage)).getCause();
    }
}
