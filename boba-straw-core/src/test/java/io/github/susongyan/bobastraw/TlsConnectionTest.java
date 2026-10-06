package io.github.susongyan.bobastraw;

import io.github.susongyan.bobastraw.protocol.RespCodec;
import io.github.susongyan.bobastraw.protocol.RespValue;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

/** Real JSSE handshakes over explicit loopback listeners; no external Redis required. */
class TlsConnectionTest {
    @TempDir
    static Path directory;
    private static SSLContext serverContext;
    private static SSLContext trustedContext;
    private static SSLContext wrongHostContext;
    private static SSLContext wrongHostTrust;
    private static X509TrustManager trustedManager;

    @BeforeAll
    static void certificates() throws Exception {
        KeyStore valid = identity("valid", "SAN=dns:localhost,ip:127.0.0.1");
        serverContext = context(valid, true);
        trustedContext = context(valid, false);
        TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trust.init(valid);
        trustedManager = (X509TrustManager) trust.getTrustManagers()[0];
        KeyStore wrong = identity("wrong", "SAN=dns:wrong.example");
        wrongHostContext = context(wrong, true);
        wrongHostTrust = context(wrong, false);
    }

    @Test
    void tls12AndAvailableTls13NegotiateBeforeRespAndPreserveLargeBinaryReplies() throws Exception {
        for (String protocol : new String[] {"TLSv1.2", "TLSv1.3"}) {
            if (!Arrays.asList(serverContext.getSupportedSSLParameters().getProtocols()).contains(protocol)) {
                continue;
            }
            try (Server server = new Server(serverContext, protocol, false);
                 BobaStrawClient client = client(server, trustedContext)) {
                assertEquals("PONG", client.sync().ping());
                byte[] payload = new byte[131072];
                Arrays.fill(payload, (byte) 0xfe);
                assertArrayEquals(payload, ((RespValue.BlobString) client.executeBinaryAsync(
                    ascii("ECHO"), payload).toCompletableFuture().get(5, TimeUnit.SECONDS)).value);
                assertEquals("PONG", client.sync().ping());
                assertEquals("HELLO", server.firstCommand);
            }
        }
    }

    @Test
    void concurrentCommandsKeepResponseOrderAcrossTlsRecords() throws Exception {
        try (Server server = new Server(serverContext, "TLSv1.2", false);
             BobaStrawClient client = client(server, trustedContext)) {
            List<CompletableFuture<RespValue>> results = new ArrayList<CompletableFuture<RespValue>>();
            for (int index = 0; index < 150; index++) {
                results.add(client.executeAsync("ECHO", "value-" + index).toCompletableFuture());
            }
            for (int index = 0; index < results.size(); index++) {
                assertEquals("value-" + index, results.get(index).get(5, TimeUnit.SECONDS).asString());
            }
        }
    }

    @Test
    void fragmentedEncryptedRecordsSurviveAcrossSocketReads() throws Exception {
        try (Server server = new Server(serverContext, "TLSv1.2", false);
             FragmentingProxy proxy = new FragmentingProxy(server.port());
             BobaStrawClient client = BobaStrawClient.builder()
                 .endpoint("127.0.0.1", proxy.listener.getLocalPort()).tls(tlsOptions())
                 .commandTimeout(Duration.ofSeconds(5)).build()) {
            assertEquals("PONG", client.sync().ping());
            byte[] payload = new byte[32768];
            Arrays.fill(payload, (byte) 0xff);
            RespValue response = client.executeBinaryAsync(ascii("ECHO"), payload)
                .toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertArrayEquals(payload, ((RespValue.BlobString) response).value);
            assertTrue(proxy.fragments.get() >= 16);
        }
    }

    @Test
    void pipelineTransactionsBlockingAndPushUseEncryptedConnections() throws Exception {
        try (Server server = new Server(serverContext, "TLSv1.2", false);
             BobaStrawClient client = client(server, trustedContext)) {
            List<RespValue> batch = client.pipeline().command("ECHO", "first")
                .command("ECHO", "second").execute().toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertEquals("first", batch.get(0).asString());
            assertEquals("second", batch.get(1).asString());
            try (BobaStrawTransaction tx = client.transaction()) {
                assertEquals("PONG", tx.command("PING").exec().toCompletableFuture()
                    .get(5, TimeUnit.SECONDS).get(0).asString());
            }
            assertEquals(Arrays.asList("queue", "item"), client.async().blpop(1, "queue")
                .toCompletableFuture().get(5, TimeUnit.SECONDS));
            CompletableFuture<String> message = new CompletableFuture<String>();
            BobaStrawSubscription subscription = client.pubSub().subscribe("events", message::complete)
                .toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertEquals("payload", message.get(5, TimeUnit.SECONDS));
            subscription.close();
            subscription.termination().toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertEquals("PONG", client.sync().ping());
        }
    }

    @Test
    void clusterDiscoveryRefreshAndAskUseTlsOnEveryTarget() throws Exception {
        try (Server seed = new Server(serverContext, "TLSv1.2", false);
             Server next = new Server(serverContext, "TLSv1.2", false);
             BobaStrawClusterClient client = BobaStrawClusterClient.builder()
                 .seed("127.0.0.1", seed.port()).tls(tlsOptions())
                 .commandTimeout(Duration.ofSeconds(5)).build()) {
            seed.redirectPort = next.port();
            assertEquals(Integer.toString(next.port()), client.async().get("key")
                .toCompletableFuture().get(5, TimeUnit.SECONDS));
            assertTrue(next.askingCommands.get() > 0);
            seed.redirectPort = 0;
            seed.discoveredPort = next.port();
            client.refreshTopology().toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertEquals(Integer.toString(next.port()), client.async().get("key")
                .toCompletableFuture().get(5, TimeUnit.SECONDS));
            try (BobaStrawTransaction tx = client.transaction("key")) {
                assertEquals("PONG", tx.command("PING").exec().toCompletableFuture()
                    .get(5, TimeUnit.SECONDS).get(0).asString());
            }
        }
    }

    @Test
    void sentinelControlAndFailoverDataConnectionsInheritSeparateTlsPolicies() throws Exception {
        try (Server sentinel = new Server(serverContext, "TLSv1.2", false);
             Server first = new Server(serverContext, "TLSv1.2", false);
             Server next = new Server(serverContext, "TLSv1.2", false)) {
            sentinel.discoveredPort = first.port();
            try (BobaStrawSentinelClient client = BobaStrawSentinelClient.builder()
                .sentinel("127.0.0.1", sentinel.port()).masterName("tea")
                .tls(tlsOptions()).sentinelTls(tlsOptions())
                .commandTimeout(Duration.ofSeconds(5)).discoveryTimeout(Duration.ofSeconds(5)).build()) {
                assertEquals(Integer.toString(first.port()), client.async().get("key")
                    .toCompletableFuture().get(5, TimeUnit.SECONDS));
                sentinel.discoveredPort = next.port();
                client.refreshTopology().toCompletableFuture().get(5, TimeUnit.SECONDS);
                assertEquals(Integer.toString(next.port()), client.async().get("key")
                    .toCompletableFuture().get(5, TimeUnit.SECONDS));
                try (BobaStrawTransaction tx = client.transaction()) {
                    assertEquals("PONG", tx.command("PING").exec().toCompletableFuture()
                        .get(5, TimeUnit.SECONDS).get(0).asString());
                }
            }
        }
    }

    private static BobaStrawTlsOptions tlsOptions() {
        return BobaStrawTlsOptions.builder().sslContext(trustedContext).build();
    }

    @Test
    void cancelledEncryptedBatchDrainsRepliesBeforeNextCommand() throws Exception {
        try (Server server = new Server(serverContext, "TLSv1.2", false);
             BobaStrawClient client = client(server, trustedContext)) {
            assertEquals("PONG", client.sync().ping());
            CompletableFuture<List<RespValue>> batch = client.pipeline().command("ECHO", "hold")
                .command("ECHO", "discard").execute().toCompletableFuture();
            assertTrue(server.held.await(5, TimeUnit.SECONDS));
            assertTrue(batch.cancel(false));
            CompletableFuture<String> next = client.async().ping().toCompletableFuture();
            server.release.countDown();
            assertEquals("PONG", next.get(5, TimeUnit.SECONDS));
            assertTrue(batch.isCancelled());
            await(() -> client.metrics().inFlightCommands() == 0);
        }
    }

    @Test
    void timeoutAfterEncryptionRemainsPossiblyExecutedAndDoesNotShiftResponses() throws Exception {
        try (Server server = new Server(serverContext, "TLSv1.2", false);
             BobaStrawClient client = BobaStrawClient.builder().endpoint("127.0.0.1", server.port())
                 .tls(tlsOptions()).commandTimeout(Duration.ofSeconds(2)).build()) {
            assertEquals("PONG", client.sync().ping());
            CompletableFuture<RespValue> held = client.executeAsync("ECHO", "hold").toCompletableFuture();
            assertTrue(server.held.await(5, TimeUnit.SECONDS));
            java.util.concurrent.ExecutionException failure = assertThrows(
                java.util.concurrent.ExecutionException.class, () -> held.get(5, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof BobaStrawCommandTimeoutException);
            assertTrue(((BobaStrawCommandTimeoutException) failure.getCause()).mayHaveExecuted());
            server.release.countDown();
            assertEquals("PONG", client.sync().ping());
        }
    }

    @Test
    void disconnectDoesNotReplayAndReplacementPerformsANewTlsHandshake() throws Exception {
        try (Server server = new Server(serverContext, "TLSv1.2", false);
             BobaStrawClient client = client(server, trustedContext)) {
            assertEquals("PONG", client.sync().ping());
            CompletableFuture<RespValue> dropped = client.executeAsync("ECHO", "disconnect")
                .toCompletableFuture();
            java.util.concurrent.ExecutionException failure = assertThrows(
                java.util.concurrent.ExecutionException.class, () -> dropped.get(5, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof BobaStrawCommandMayHaveExecutedException);
            await(() -> client.successfulReconnects() > 0);
            assertEquals("PONG", client.sync().ping());
            assertEquals(1, server.droppedCommands.get());
            assertTrue(server.handshakes.get() >= 2);
        }
    }

    @Test
    void slowCertificateTaskDoesNotBlockOtherConnectionsAndCannotResurrectClosedClient() throws Exception {
        CountDownLatch verifying = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        SSLContext slowContext = SSLContext.getInstance("TLS");
        slowContext.init(null, new TrustManager[] {new X509TrustManager() {
            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return trustedManager.getAcceptedIssuers();
            }

            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                trustedManager.checkClientTrusted(chain, authType);
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                verifying.countDown();
                try {
                    // Deliberately simulate application code that ignores cancellation interrupts.
                    while (resume.getCount() != 0) {
                        try {
                            if (!resume.await(5, TimeUnit.SECONDS)) {
                                throw new CertificateException("Fixture trust task timed out");
                            }
                        } catch (InterruptedException ignored) {
                            // The production close path must not wait for this code.
                        }
                    }
                    trustedManager.checkServerTrusted(chain, authType);
                } finally {
                    finished.countDown();
                }
            }
        }}, null);
        try (Server slow = new Server(serverContext, "TLSv1.2", false);
             Server fast = new Server(serverContext, "TLSv1.2", false);
             BobaStrawClientResources resources = BobaStrawClientResources.builder().build();
             BobaStrawClient stalled = BobaStrawClient.builder().resources(resources)
                 .endpoint("127.0.0.1", slow.port())
                 .tls(BobaStrawTlsOptions.builder().sslContext(slowContext).build()).build();
             BobaStrawClient healthy = BobaStrawClient.builder().resources(resources)
                 .endpoint("127.0.0.1", fast.port()).tls(tlsOptions())
                 .commandTimeout(Duration.ofSeconds(5)).build()) {
            try {
                CompletableFuture<String> request = stalled.async().ping().toCompletableFuture();
                assertTrue(verifying.await(5, TimeUnit.SECONDS));
                assertEquals("PONG", healthy.sync().ping());
                stalled.close();
                assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> request.get(2, TimeUnit.SECONDS));
                resume.countDown();
                assertTrue(finished.await(5, TimeUnit.SECONDS));
                assertEquals("PONG", healthy.sync().ping());
                assertEquals(0, slow.commands.get());
                assertEquals(BobaStrawConnectionState.CLOSED, stalled.metrics().sharedConnectionState());
            } finally {
                resume.countDown();
            }
        }
    }

    private static void await(java.util.function.BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertTrue(condition.getAsBoolean());
    }

    @Test
    void untrustedAndWrongHostCertificatesFailBeforeAnyRedisCommand() throws Exception {
        try (Server server = new Server(serverContext, "TLSv1.2", false);
             BobaStrawClient client = client(server, SSLContext.getDefault())) {
            assertThrows(BobaStrawCommandNotSentException.class, () -> client.sync().ping());
            assertEquals(0, server.commands.get());
        }
        try (Server server = new Server(wrongHostContext, "TLSv1.2", false);
             BobaStrawClient client = client(server, wrongHostTrust)) {
            assertThrows(BobaStrawCommandNotSentException.class, () -> client.sync().ping());
            assertEquals(0, server.commands.get());
        }
    }

    @Test
    void expiredLeafSignedByTrustedCaIsRejected() throws Exception {
        KeyStore ca = identity("ca", "BC=ca:true");
        identity("expired", "SAN=dns:localhost,ip:127.0.0.1");
        Path caStore = directory.resolve("ca.jks");
        Path leafStore = directory.resolve("expired.jks");
        Path request = directory.resolve("expired.csr");
        Path certificate = directory.resolve("expired.crt");
        Path root = directory.resolve("ca.crt");
        keytool("-certreq", "-alias", "identity", "-keystore", leafStore.toString(),
            "-file", request.toString());
        keytool("-gencert", "-alias", "identity", "-keystore", caStore.toString(),
            "-infile", request.toString(), "-outfile", certificate.toString(),
            "-startdate", "2020/01/01 00:00:00", "-validity", "1", "-rfc",
            "-ext", "SAN=dns:localhost,ip:127.0.0.1");
        keytool("-exportcert", "-alias", "identity", "-keystore", caStore.toString(),
            "-file", root.toString(), "-rfc");
        keytool("-importcert", "-alias", "root", "-keystore", leafStore.toString(),
            "-file", root.toString(), "-noprompt");
        keytool("-importcert", "-alias", "identity", "-keystore", leafStore.toString(),
            "-file", certificate.toString(), "-noprompt");
        try (Server server = new Server(context(load(leafStore), true), "TLSv1.2", false);
             BobaStrawClient client = client(server, context(ca, false))) {
            assertThrows(BobaStrawCommandNotSentException.class, () -> client.sync().ping());
            assertEquals(0, server.commands.get());
        }
    }

    @Test
    void redissCannotBeDowngradedWithNullOptionsAndUnknownSchemesAreRejected() throws Exception {
        assertThrows(IllegalArgumentException.class,
            () -> BobaStrawClient.builder().uri("http://localhost:6379"));
        try (Server server = new Server(serverContext, "TLSv1.2", false);
             BobaStrawClient client = BobaStrawClient.builder()
                 .uri("rediss://127.0.0.1:" + server.port()).tls(null)
                 .commandTimeout(Duration.ofSeconds(5)).build()) {
            assertThrows(BobaStrawCommandNotSentException.class, () -> client.sync().ping());
            assertEquals(0, server.commands.get());
        }
    }

    @Test
    void mutualTlsRequiresAnIdentity() throws Exception {
        try (Server server = new Server(serverContext, "TLSv1.2", true);
             BobaStrawClient missingIdentity = client(server, trustedContext)) {
            assertThrows(BobaStrawCommandNotSentException.class, () -> missingIdentity.sync().ping());
            assertEquals(0, server.commands.get());
        }
        try (Server server = new Server(serverContext, "TLSv1.2", true);
             BobaStrawClient withIdentity = client(server, serverContext)) {
            assertEquals("PONG", withIdentity.sync().ping());
        }
    }

    @Test
    void stalledHandshakeTimesOutWithoutSendingRedisAndOtherConnectionsRemainUsable() throws Exception {
        try (ServerSocket listener = LoopbackTestServer.open();
             Server healthy = new Server(serverContext, "TLSv1.2", false);
             BobaStrawClientResources resources = BobaStrawClientResources.builder().build();
             BobaStrawClient stalled = BobaStrawClient.builder().resources(resources)
                 .endpoint("127.0.0.1", listener.getLocalPort())
                 .tls(BobaStrawTlsOptions.builder().sslContext(trustedContext)
                     .handshakeTimeout(Duration.ofMillis(250)).build())
                 .commandTimeout(Duration.ofSeconds(5)).build();
             Socket accepted = listener.accept();
             BobaStrawClient other = BobaStrawClient.builder().resources(resources)
                 .endpoint("127.0.0.1", healthy.port())
                 .tls(BobaStrawTlsOptions.builder().sslContext(trustedContext).build())
                 .commandTimeout(Duration.ofSeconds(5)).build()) {
            assertThrows(BobaStrawCommandNotSentException.class, () -> stalled.sync().ping());
            assertEquals("PONG", other.sync().ping());
            accepted.setSoTimeout(2000);
            byte[] received = new byte[4096];
            int count = accepted.getInputStream().read(received);
            assertTrue(count > 0);
            assertEquals(22, received[0]); // TLS handshake, never a RESP AUTH/HELLO frame.
        }
    }

    private static BobaStrawClient client(Server server, SSLContext context) {
        return BobaStrawClient.builder().uri("rediss://127.0.0.1:" + server.port())
            .tls(BobaStrawTlsOptions.builder().sslContext(context).build())
            .commandTimeout(Duration.ofSeconds(5)).build();
    }

    private static KeyStore identity(String name, String san) throws Exception {
        Path store = directory.resolve(name + ".jks");
        keytool("-genkeypair", "-alias", "identity", "-keystore", store.toString(),
            "-keyalg", "RSA", "-keysize", "2048", "-validity", "2",
            "-dname", "CN=TLS fixture", "-ext", san, "-noprompt");
        return load(store);
    }

    private static void keytool(String... arguments) throws Exception {
        Path keytool = Paths.get(System.getProperty("java.home"), "bin", "keytool");
        if (!Files.exists(keytool)) {
            keytool = Paths.get(System.getProperty("java.home"), "..", "bin", "keytool");
        }
        Path log = Files.createTempFile(directory, "keytool-", ".log");
        List<String> command = new ArrayList<String>();
        command.add(keytool.toString());
        command.addAll(Arrays.asList(arguments));
        command.addAll(Arrays.asList("-storetype", "JKS", "-storepass", "test-only",
            "-keypass", "test-only"));
        Process process = new ProcessBuilder(command)
            .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        if (!process.waitFor(20, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            fail("keytool did not terminate");
        }
        assertEquals(0, process.exitValue(), new String(Files.readAllBytes(log), StandardCharsets.UTF_8));
    }

    private static KeyStore load(Path store) throws Exception {
        KeyStore result = KeyStore.getInstance("JKS");
        try (InputStream input = Files.newInputStream(store)) {
            result.load(input, "test-only".toCharArray());
        }
        return result;
    }

    private static SSLContext context(KeyStore identity, boolean clientIdentity) throws Exception {
        TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trust.init(identity);
        KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keys.init(identity, "test-only".toCharArray());
        SSLContext result = SSLContext.getInstance("TLS");
        result.init(clientIdentity ? keys.getKeyManagers() : null, trust.getTrustManagers(), null);
        return result;
    }

    private static byte[] ascii(String value) {
        return value.getBytes(StandardCharsets.US_ASCII);
    }

    /** Splits TLS ciphertext itself, independently of the RESP and TLS record boundaries. */
    @Test
    void truncatedCiphertextFailsThePendingCommandWithoutReturningPartialData() throws Exception {
        try (Server server = new Server(serverContext, "TLSv1.2", false);
             FragmentingProxy proxy = new FragmentingProxy(server.port());
             BobaStrawClient client = BobaStrawClient.builder()
                 .endpoint("127.0.0.1", proxy.listener.getLocalPort()).tls(tlsOptions())
                 .commandTimeout(Duration.ofSeconds(5)).build()) {
            assertEquals("PONG", client.sync().ping());
            proxy.truncateServer = true;
            CompletableFuture<RespValue> reply = client.executeAsync("ECHO", "never-partial-success")
                .toCompletableFuture();
            java.util.concurrent.ExecutionException failure = assertThrows(
                java.util.concurrent.ExecutionException.class, () -> reply.get(5, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof BobaStrawCommandMayHaveExecutedException);
            Throwable cause = failure.getCause();
            while (cause != null && !(cause instanceof javax.net.ssl.SSLException)) {
                cause = cause.getCause();
            }
            assertNotNull(cause, "Truncation must retain the TLS error cause");
        }
    }

    @Test
    void discoveredAndAskTargetsCannotBypassCertificateValidation() throws Exception {
        try (Server seed = new Server(serverContext, "TLSv1.2", false);
             Server rejected = new Server(wrongHostContext, "TLSv1.2", false);
             BobaStrawClusterClient cluster = BobaStrawClusterClient.builder()
                 .seed("127.0.0.1", seed.port()).tls(tlsOptions())
                 .commandTimeout(Duration.ofSeconds(5)).build()) {
            seed.redirectPort = rejected.port();
            java.util.concurrent.ExecutionException ask = assertThrows(
                java.util.concurrent.ExecutionException.class,
                () -> cluster.async().get("key").toCompletableFuture().get(5, TimeUnit.SECONDS));
            assertTrue(ask.getCause() instanceof BobaStrawConnectionException);
            assertEquals(0, rejected.commands.get());
            seed.redirectPort = 0;
            seed.discoveredPort = rejected.port();
            cluster.refreshTopology().toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertThrows(java.util.concurrent.ExecutionException.class,
                () -> cluster.async().get("key").toCompletableFuture().get(5, TimeUnit.SECONDS));
            assertEquals(0, rejected.commands.get());
        }
    }

    private static final class FragmentingProxy implements AutoCloseable {
        private final ServerSocket listener = LoopbackTestServer.open();
        private final ExecutorService workers = Executors.newFixedThreadPool(3);
        private final AtomicInteger fragments = new AtomicInteger();
        private volatile Socket front;
        private volatile Socket back;
        private volatile boolean truncateServer;

        FragmentingProxy(int target) throws Exception {
            workers.execute(() -> {
                try {
                    Socket accepted = listener.accept();
                    synchronized (this) {
                        if (listener.isClosed()) {
                            accepted.close();
                            return;
                        }
                        front = accepted;
                        front.setTcpNoDelay(true);
                        back = new Socket("127.0.0.1", target);
                        back.setTcpNoDelay(true);
                        workers.execute(() -> forward(front, back));
                        workers.execute(() -> forward(back, front));
                    }
                } catch (Exception error) {
                    if (!listener.isClosed()) {
                        throw new AssertionError(error);
                    }
                }
            });
        }

        private void forward(Socket source, Socket target) {
            try {
                InputStream input = source.getInputStream();
                OutputStream output = target.getOutputStream();
                // Separate the record header bytes in time to exercise BUFFER_UNDERFLOW.
                for (int index = 0; index < 8; index++) {
                    int value = input.read();
                    if (value < 0) {
                        return;
                    }
                    output.write(value);
                    output.flush();
                    fragments.incrementAndGet();
                    Thread.sleep(5);
                }
                byte[] bytes = new byte[113];
                int count;
                while ((count = input.read(bytes)) >= 0) {
                    if (source == back && truncateServer && count > 0) {
                        output.write(bytes[0]);
                        output.flush();
                        target.shutdownOutput();
                        return;
                    }
                    output.write(bytes, 0, count);
                    output.flush();
                }
            } catch (Exception ignoredOnClose) {
                // The client assertions detect premature termination.
            }
        }

        @Override
        public void close() throws Exception {
            listener.close();
            synchronized (this) {
                if (front != null) {
                    front.close();
                }
                if (back != null) {
                    back.close();
                }
            }
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private static final class Server implements AutoCloseable {
        private final SSLServerSocket listener;
        private final List<Socket> sockets = new ArrayList<Socket>();
        private final ExecutorService workers = Executors.newCachedThreadPool(action -> {
            Thread thread = new Thread(action, "boba-tls-fixture");
            thread.setDaemon(true);
            return thread;
        });
        private final AtomicInteger commands = new AtomicInteger();
        private final AtomicInteger askingCommands = new AtomicInteger();
        private final AtomicInteger handshakes = new AtomicInteger();
        private final AtomicInteger droppedCommands = new AtomicInteger();
        private final CountDownLatch held = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private volatile String firstCommand;
        private volatile int discoveredPort;
        private volatile int redirectPort;

        Server(SSLContext context, String protocol, boolean mutual) throws Exception {
            listener = (SSLServerSocket) context.getServerSocketFactory().createServerSocket();
            listener.setReuseAddress(false);
            listener.bind(new InetSocketAddress("127.0.0.1", 0));
            discoveredPort = port();
            listener.setEnabledProtocols(new String[] {protocol});
            listener.setNeedClientAuth(mutual);
            workers.execute(() -> {
                while (!listener.isClosed()) {
                    try {
                        SSLSocket socket = (SSLSocket) listener.accept();
                        synchronized (sockets) {
                            if (listener.isClosed()) {
                                socket.close();
                                return;
                            }
                            sockets.add(socket);
                            workers.execute(() -> serve(socket));
                        }
                    } catch (Exception error) {
                        if (!listener.isClosed()) {
                            throw new AssertionError(error);
                        }
                    }
                }
            });
        }

        int port() {
            return listener.getLocalPort();
        }

        private void serve(SSLSocket socket) {
            try (SSLSocket owned = socket) {
                owned.setSoTimeout(5000);
                owned.startHandshake();
                handshakes.incrementAndGet();
                InputStream input = owned.getInputStream();
                OutputStream output = owned.getOutputStream();
                RespCodec.Decoder decoder = new RespCodec.Decoder();
                byte[] buffer = new byte[997];
                int queued = -1;
                int length;
                while ((length = input.read(buffer)) >= 0) {
                    decoder.feed(buffer, length);
                    RespValue value;
                    while ((value = decoder.poll()) != null) {
                        List<RespValue> arguments = ((RespValue.Array) value).values;
                        String name = arguments.get(0).asString();
                        if (commands.getAndIncrement() == 0) {
                            firstCommand = name;
                        }
                        if ("HELLO".equals(name)) {
                            output.write(ascii("%1\r\n+proto\r\n:3\r\n"));
                        } else if ("CLUSTER".equals(name)) {
                            output.write(ascii("*1\r\n*3\r\n:0\r\n:16383\r\n*2\r\n"
                                + "$9\r\n127.0.0.1\r\n:" + discoveredPort + "\r\n"));
                        } else if ("SENTINEL".equals(name)) {
                            String address = Integer.toString(discoveredPort);
                            output.write(ascii("*2\r\n$9\r\n127.0.0.1\r\n$" + address.length()
                                + "\r\n" + address + "\r\n"));
                        } else if ("ROLE".equals(name)) {
                            output.write(ascii("*3\r\n+master\r\n:0\r\n*0\r\n"));
                        } else if ("ASKING".equals(name)) {
                            askingCommands.incrementAndGet();
                            output.write(ascii("+OK\r\n"));
                        } else if ("MULTI".equals(name)) {
                            queued = 0;
                            output.write(ascii("+OK\r\n"));
                        } else if ("EXEC".equals(name)) {
                            output.write(ascii("*" + queued + "\r\n"));
                            for (int index = 0; index < queued; index++) {
                                output.write(ascii("+PONG\r\n"));
                            }
                            queued = -1;
                        } else if (queued >= 0) {
                            queued++;
                            output.write(ascii("+QUEUED\r\n"));
                        } else if ("UNWATCH".equals(name)) {
                            output.write(ascii("+OK\r\n"));
                        } else if ("BLPOP".equals(name)) {
                            output.write(ascii("*2\r\n+queue\r\n+item\r\n"));
                        } else if ("SUBSCRIBE".equals(name)) {
                            output.write(ascii(">3\r\n+subscribe\r\n+events\r\n:1\r\n"
                                + ">3\r\n+message\r\n+events\r\n+payload\r\n"));
                        } else if ("UNSUBSCRIBE".equals(name)) {
                            output.write(ascii(">3\r\n+unsubscribe\r\n+events\r\n:0\r\n"));
                        } else if ("GET".equals(name)) {
                            if (redirectPort > 0) {
                                output.write(ascii("-ASK " + ClusterSlot.of(arguments.get(1).asString())
                                    + " 127.0.0.1:" + redirectPort + "\r\n"));
                            } else {
                                String valueText = Integer.toString(port());
                                output.write(ascii("$" + valueText.length() + "\r\n" + valueText + "\r\n"));
                            }
                        } else if ("ECHO".equals(name)) {
                            byte[] payload = ((RespValue.BlobString) arguments.get(1)).value;
                            if (Arrays.equals(ascii("disconnect"), payload)) {
                                droppedCommands.incrementAndGet();
                                return;
                            }
                            if (Arrays.equals(ascii("hold"), payload)) {
                                held.countDown();
                                if (!release.await(5, TimeUnit.SECONDS)) {
                                    throw new IllegalStateException("Fixture reply gate timed out");
                                }
                            }
                            output.write(ascii("$" + payload.length + "\r\n"));
                            for (int offset = 0; offset < payload.length; offset += 997) {
                                output.write(payload, offset, Math.min(997, payload.length - offset));
                            }
                            output.write(ascii("\r\n"));
                        } else {
                            output.write(ascii("+PONG\r\n"));
                        }
                        output.flush();
                    }
                }
            } catch (Exception expectedOnRejectionOrClose) {
                // Negative TLS tests deliberately reject the handshake; assertions are client-side.
            }
        }

        @Override
        public void close() throws Exception {
            release.countDown();
            listener.close();
            synchronized (sockets) {
                for (Socket socket : sockets) {
                    socket.close();
                }
            }
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }
}
