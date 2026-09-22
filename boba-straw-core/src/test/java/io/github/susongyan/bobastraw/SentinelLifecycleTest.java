package io.github.susongyan.bobastraw;

import io.github.susongyan.bobastraw.protocol.RespCodec;
import io.github.susongyan.bobastraw.protocol.RespValue;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

@Tag("fault-injection")
class SentinelLifecycleTest {
    @Test
    void separateAuthenticationAndRoleVerificationPrecedeCommands() throws Exception {
        try (Peer sentinel = new Peer(); Peer data = new Peer()) {
            sentinel.destination = data.port();
            sentinel.password = "sentinel-secret";
            data.password = "redis-secret";
            try (BobaStrawSentinelClient client = builder(sentinel)
                .sentinelCredentials(null, "sentinel-secret").credentials(null, "redis-secret").build()) {
                assertEquals("tea", get(client));
                assertTrue(data.roleCalls.get() > 0);
                assertEquals(BobaStrawConnectionState.READY, client.connectionState());
                assertEquals("[127.0.0.1]:" + data.port(), client.masterAddress());
                assertEquals(0, sentinel.businessCalls.get());
            }
            await(() -> data.live.get() == 0 && sentinel.live.get() == 0);
        }
    }

    @Test
    void preferredSentinelFallsBackWhenMasterBecomesUnknown() throws Exception {
        try (Peer first = new Peer(); Peer second = new Peer(); Peer data = new Peer()) {
            first.destination = data.port();
            second.destination = data.port();
            try (BobaStrawSentinelClient client = builder(first).sentinel("127.0.0.1", second.port()).build()) {
                Peer preferred = first.discoveries.get() > 0 ? first : second;
                Peer fallback = preferred == first ? second : first;
                preferred.destination = 0;
                client.refreshTopology().toCompletableFuture().get(2, TimeUnit.SECONDS);
                assertTrue(fallback.discoveries.get() > 0);
                assertEquals("tea", get(client));
                assertEquals(1, data.accepted.get(), "Same primary must reuse its physical connection");
            }
        }
    }

    @Test
    void staleReplicaAndAuthenticationFailureDoNotReceiveBusinessCommands() throws Exception {
        try (Peer sentinel = new Peer(); Peer data = new Peer();
             BobaStrawClientResources resources = BobaStrawClientResources.builder().build()) {
            sentinel.destination = data.port();
            data.role = "slave";
            assertThrows(BobaStrawConnectionException.class, () -> builder(sentinel).resources(resources).build());
            await(() -> data.live.get() == 0);
            assertTrue(resources.isOpen());
            data.role = "master";
            data.password = "required";
            BobaStrawConnectionException error = assertThrows(BobaStrawConnectionException.class,
                () -> builder(sentinel).credentials(null, "wrong").build());
            assertTrue(causes(error).contains("WRONGPASS"), causes(error));
            assertEquals(0, data.businessCalls.get());
        }
    }

    @Test
    void unknownNameIsReportedAndFailedBootstrapClosesDiscoverySocket() throws Exception {
        try (Peer sentinel = new Peer()) {
            BobaStrawConnectionException error = assertThrows(BobaStrawConnectionException.class,
                () -> builder(sentinel).build());
            assertTrue(causes(error).contains("do not know master name"));
            await(() -> sentinel.live.get() == 0);
        }
    }

    @Test
    void switchingPrimaryRetiresUnansweredWriteAsAmbiguousWithoutReplaying() throws Exception {
        try (Peer sentinel = new Peer(); Peer old = new Peer(); Peer replacement = new Peer()) {
            sentinel.destination = old.port();
            try (BobaStrawSentinelClient client = builder(sentinel).build()) {
                old.holdBusiness = true;
                CompletableFuture<RespValue> pending = client.executeAsync("SET", "key", "value").toCompletableFuture();
                await(() -> old.businessCalls.get() == 1);
                sentinel.destination = replacement.port();
                client.refreshTopology().toCompletableFuture().get(2, TimeUnit.SECONDS);
                assertTrue(failure(pending) instanceof BobaStrawCommandMayHaveExecutedException);
                assertEquals(0, replacement.businessCalls.get());
                await(() -> old.live.get() == 0);
                assertEquals("tea", get(client));
                assertEquals(1, replacement.businessCalls.get());
            }
        }
    }

    @Test
    void standaloneBackedClusterNodeRetirementPreservesPendingWriteClassification() throws Exception {
        try (Peer data = new Peer(); BobaStrawClient client = BobaStrawClient.builder()
            .endpoint("127.0.0.1", data.port()).protocol(ProtocolVersion.RESP2).build()) {
            data.holdBusiness = true;
            CompletableFuture<RespValue> write = client.executeAsync("SET", "key", "value").toCompletableFuture();
            await(() -> data.businessCalls.get() == 1);
            client.retireForTopologyChange();
            assertTrue(failure(write) instanceof BobaStrawCommandMayHaveExecutedException);
            await(() -> data.live.get() == 0);
            assertEquals(BobaStrawConnectionState.CLOSED, client.metrics().sharedConnectionState());
            assertEquals(1, data.businessCalls.get());
        }
    }

    @Test
    void disconnectionRediscoversInsteadOfReconnectingOldAddress() throws Exception {
        try (Peer sentinel = new Peer(); Peer old = new Peer(); Peer replacement = new Peer()) {
            sentinel.destination = old.port();
            try (BobaStrawSentinelClient client = builder(sentinel).build()) {
                sentinel.destination = replacement.port();
                sentinel.holdDiscovery = true;
                old.dropBusiness = true;
                assertTrue(failure(client.executeAsync("SET", "key", "value").toCompletableFuture())
                    instanceof BobaStrawCommandMayHaveExecutedException);
                await(() -> client.connectionState() != BobaStrawConnectionState.READY);
                assertTrue(failure(client.executeAsync("GET", "key").toCompletableFuture())
                    instanceof BobaStrawCommandNotSentException);
                sentinel.holdDiscovery = false;
                await(() -> ("[127.0.0.1]:" + replacement.port()).equals(client.masterAddress()));
                assertEquals(1, old.accepted.get());
                assertEquals(0, replacement.businessCalls.get());
                assertEquals("tea", get(client));
            }
        }
    }

    @Test
    void periodicDiscoveryChangesPrimaryWithoutBusinessTraffic() throws Exception {
        try (Peer sentinel = new Peer(); Peer old = new Peer(); Peer replacement = new Peer()) {
            sentinel.destination = old.port();
            try (BobaStrawSentinelClient client = builder(sentinel)
                .topologyRefreshInterval(Duration.ofMillis(50)).build()) {
                sentinel.destination = replacement.port();
                await(() -> ("[127.0.0.1]:" + replacement.port()).equals(client.masterAddress()));
                assertEquals(0, old.businessCalls.get());
                assertEquals(0, replacement.businessCalls.get());
                await(() -> old.live.get() == 0);
            }
        }
    }

    @Test
    void failedDiscoveryPreservesVerifiedPrimaryAndRejectsStatefulCommands() throws Exception {
        try (Peer sentinel = new Peer(); Peer data = new Peer()) {
            sentinel.destination = data.port();
            try (BobaStrawSentinelClient client = builder(sentinel).build()) {
                sentinel.destination = 0;
                failure(client.refreshTopology().toCompletableFuture());
                assertEquals("tea", get(client));
                assertEquals(1, client.failedDiscoveries());
                assertThrows(IllegalArgumentException.class, () -> client.executeAsync("MULTI"));
                assertThrows(IllegalArgumentException.class, () -> client.executeAsync("BLPOP", "key", "0"));
                assertThrows(IllegalArgumentException.class, () -> client.executeAsync("SUBSCRIBE", "key"));
                assertEquals(1, data.businessCalls.get());
            }
        }
    }

    @Test
    void roleAdmissionBackpressureDoesNotRetireHealthyBusyPrimary() throws Exception {
        try (Peer sentinel = new Peer(); Peer data = new Peer()) {
            sentinel.destination = data.port();
            try (BobaStrawSentinelClient client = builder(sentinel).connectionLimits(
                BobaStrawConnectionLimits.builder().maxInFlightCommands(1).build()).build()) {
                data.holdBusiness = true;
                CompletableFuture<RespValue> busy = client.executeAsync("GET", "key").toCompletableFuture();
                await(() -> data.businessCalls.get() == 1);
                Throwable error = failure(client.refreshTopology().toCompletableFuture());
                assertTrue(error.getCause() instanceof BobaStrawBackpressureException);
                assertEquals(BobaStrawConnectionState.READY, client.connectionState());
                assertEquals(1, data.roleCalls.get(), "Rejected ROLE must not be sent");
                assertEquals(1, data.live.get());
                assertFalse(busy.isDone());
                busy.cancel(false);
            }
        }
    }

    @Test
    void closeTerminatesRefreshAndKeepsExternalResourcesOpen() throws Exception {
        try (Peer sentinel = new Peer(); Peer data = new Peer();
             BobaStrawClientResources resources = BobaStrawClientResources.builder().build()) {
            sentinel.destination = data.port();
            BobaStrawSentinelClient client = builder(sentinel).resources(resources).build();
            try {
                data.holdRole = true;
                CompletableFuture<Void> refresh = client.refreshTopology().toCompletableFuture();
                await(() -> data.roleCalls.get() == 2);
                client.close();
                failure(refresh);
                await(() -> data.live.get() == 0);
                assertTrue(resources.isOpen());
                assertEquals(BobaStrawConnectionState.CLOSED, client.connectionState());
                assertThrows(BobaStrawConnectionException.class, () -> client.executeAsync("PING"));
            } finally {
                client.close();
            }
        }
    }

    @Test
    void cancelledRefreshViewDoesNotCancelDiscoveryOrRunCallbacksOnSelector() throws Exception {
        try (Peer sentinel = new Peer(); Peer data = new Peer()) {
            sentinel.destination = data.port();
            try (BobaStrawSentinelClient client = builder(sentinel).build()) {
                long before = client.successfulDiscoveries();
                client.refreshTopology().toCompletableFuture().cancel(false);
                await(() -> client.successfulDiscoveries() > before);
                AtomicReference<String> thread = new AtomicReference<String>();
                client.refreshTopology().thenRun(() -> thread.set(Thread.currentThread().getName()))
                    .toCompletableFuture().get(2, TimeUnit.SECONDS);
                assertFalse(thread.get().startsWith("boba-straw-nio"));
            }
        }
    }

    private static BobaStrawSentinelClient.Builder builder(Peer sentinel) {
        return BobaStrawSentinelClient.builder().sentinel("127.0.0.1", sentinel.port()).masterName("tea")
            .protocol(ProtocolVersion.RESP2).commandTimeout(Duration.ofSeconds(1))
            .discoveryTimeout(Duration.ofMillis(300)).topologyRefreshInterval(Duration.ofSeconds(30))
            .reconnectInterval(Duration.ofMillis(50)).reconnectMaxInterval(Duration.ofMillis(200));
    }

    private static String get(BobaStrawSentinelClient client) throws Exception {
        return client.executeAsync("GET", "key").toCompletableFuture().get(2, TimeUnit.SECONDS).asString();
    }

    private static Throwable failure(CompletableFuture<?> future) throws Exception {
        Throwable error = assertThrows(java.util.concurrent.ExecutionException.class,
            () -> future.get(3, TimeUnit.SECONDS));
        while (error instanceof java.util.concurrent.ExecutionException
            || error instanceof java.util.concurrent.CompletionException) {
            error = error.getCause();
        }
        return error;
    }

    private static String causes(Throwable error) {
        StringBuilder result = new StringBuilder();
        for (Throwable current = error; current != null; current = current.getCause()) {
            result.append(current.getMessage()).append('\n');
        }
        return result.toString();
    }

    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertTrue(condition.getAsBoolean());
    }

    private static final class Peer implements AutoCloseable {
        final ServerSocket server = new ServerSocket(0);
        final List<Socket> sockets = new CopyOnWriteArrayList<Socket>();
        final List<Thread> sessions = new CopyOnWriteArrayList<Thread>();
        final AtomicReference<Throwable> error = new AtomicReference<Throwable>();
        final AtomicInteger live = new AtomicInteger();
        final AtomicInteger accepted = new AtomicInteger();
        final AtomicInteger discoveries = new AtomicInteger();
        final AtomicInteger roleCalls = new AtomicInteger();
        final AtomicInteger businessCalls = new AtomicInteger();
        final Thread acceptor;
        volatile int destination;
        volatile String role = "master";
        volatile String password;
        volatile boolean holdBusiness;
        volatile boolean dropBusiness;
        volatile boolean holdRole;
        volatile boolean holdDiscovery;
        volatile boolean closing;

        Peer() throws Exception {
            acceptor = new Thread(() -> {
                while (!closing) {
                    try {
                        Socket socket = server.accept();
                        sockets.add(socket);
                        live.incrementAndGet();
                        accepted.incrementAndGet();
                        Thread thread = new Thread(() -> serve(socket), "sentinel-test-session");
                        sessions.add(thread);
                        thread.setDaemon(true);
                        thread.start();
                    } catch (Exception failure) {
                        if (!closing) {
                            error.compareAndSet(null, failure);
                        }
                    }
                }
            }, "sentinel-test-acceptor");
            acceptor.setDaemon(true);
            acceptor.start();
        }

        int port() {
            return server.getLocalPort();
        }

        private void serve(Socket socket) {
            try {
                RespCodec.Decoder decoder = new RespCodec.Decoder();
                byte[] buffer = new byte[4096];
                boolean authenticated = password == null;
                int read;
                while ((read = socket.getInputStream().read(buffer)) != -1) {
                    decoder.feed(buffer, read);
                    RespValue value;
                    while ((value = decoder.poll()) != null) {
                        List<RespValue> command = ((RespValue.Array) value).values;
                        String name = command.get(0).asString();
                        String response;
                        if ("AUTH".equals(name)) {
                            authenticated = password != null && password.equals(command.get(command.size() - 1).asString());
                            response = authenticated ? "+OK\r\n" : "-WRONGPASS invalid credentials\r\n";
                        } else if (!authenticated) {
                            response = "-NOAUTH Authentication required\r\n";
                        } else if ("SENTINEL".equals(name)) {
                            discoveries.incrementAndGet();
                            if (holdDiscovery) {
                                continue;
                            }
                            response = destination == 0 ? "*-1\r\n"
                                : "*2\r\n+127.0.0.1\r\n+" + destination + "\r\n";
                        } else if ("ROLE".equals(name)) {
                            roleCalls.incrementAndGet();
                            if (holdRole) {
                                continue;
                            }
                            response = "*3\r\n+" + role + "\r\n:0\r\n*0\r\n";
                        } else {
                            businessCalls.incrementAndGet();
                            if (dropBusiness) {
                                return;
                            }
                            if (holdBusiness) {
                                continue;
                            }
                            response = "+tea\r\n";
                        }
                        socket.getOutputStream().write(response.getBytes(StandardCharsets.UTF_8));
                        socket.getOutputStream().flush();
                    }
                }
            } catch (Throwable failure) {
                if (!closing && !(failure instanceof java.net.SocketException)) {
                    error.compareAndSet(null, failure);
                }
            } finally {
                try {
                    socket.close();
                } catch (Exception ignored) {
                    // Test fixture owner may already have closed it.
                }
                live.decrementAndGet();
            }
        }

        @Override
        public void close() throws Exception {
            closing = true;
            server.close();
            acceptor.join(2000);
            for (Socket socket : sockets) {
                socket.close();
            }
            for (Thread thread : sessions) {
                thread.join(2000);
            }
            assertNull(error.get());
            assertEquals(0, live.get());
        }
    }
}
