package io.github.susongyan.bobastraw;

import io.github.susongyan.bobastraw.protocol.RespCodec;
import io.github.susongyan.bobastraw.protocol.RespValue;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

@Tag("fault-injection")
class ClusterLifecycleTest {
    @Test
    void scriptOptionsApplyToClusterRegistry() throws Exception {
        try (Peer peer = new Peer(); BobaStrawClusterClient client = builder(peer)
            .scriptOptions(BobaStrawScriptOptions.builder().maxRegisteredScripts(1).build()).build()) {
            client.scripts().register("one", "return 1", ScriptOutput.integer());
            assertThrows(BobaStrawBackpressureException.class,
                () -> client.scripts().register("two", "return 2", ScriptOutput.integer()));
        }
    }

    @Test
    void registeredScriptsReevaluateHintsAfterMoved() throws Exception {
        try (Peer source = new Peer(); Peer destination = new Peer();
             BobaStrawClusterClient client = builder(source).build()) {
            String key = "script-moved";
            source.redirect = "MOVED " + ClusterSlot.of(key) + " 127.0.0.1:" + destination.port();
            source.slots = Peer.slots(destination.port());
            client.scripts().register("read", "return 'tea'", ScriptOutput.string());
            for (int index = 0; index < 2; index++) {
                assertEquals("tea", client.scripts().execute("read", ScriptOutput.string(), new String[] {key})
                    .toCompletableFuture().get(2, TimeUnit.SECONDS));
            }
            assertEquals(1, source.appCommands.get());
            assertEquals(2, destination.appCommands.get());
            List<String> scriptCommands = new java.util.ArrayList<String>();
            for (Session session : destination.sessions) {
                for (String command : session.commands) {
                    if (command.startsWith("EVAL")) {
                        scriptCommands.add(command);
                    }
                }
            }
            assertEquals(Arrays.asList("EVAL", "EVALSHA"), scriptCommands);
            destination.redirect = "MOVED " + ClusterSlot.of(key) + " 127.0.0.1:" + source.port();
            assertTrue(failure(client.scripts().execute("read", ScriptOutput.string(), new String[] {key})
                .toCompletableFuture()).getMessage().startsWith("MOVED "));
        }
    }

    @Test
    void cancellingRegisteredScriptDuringAskingClosesItsDedicatedSocket() throws Exception {
        try (Peer source = new Peer(); Peer destination = new Peer();
             BobaStrawClusterClient client = builder(source).build()) {
            String key = "script-ask";
            source.redirect = "ASK " + ClusterSlot.of(key) + " 127.0.0.1:" + destination.port();
            destination.holdAsking = true;
            client.scripts().register("read", "return 'tea'", ScriptOutput.string());
            CompletableFuture<String> result = client.scripts().execute("read", ScriptOutput.string(),
                new String[] {key}).toCompletableFuture();
            assertTrue(destination.askReceived.await(2, TimeUnit.SECONDS));
            assertTrue(result.cancel(false));
            assertTrue(destination.sessions.get(0).closed.await(2, TimeUnit.SECONDS));
            assertEquals(0, destination.appCommands.get());
        }
    }

    @Test
    void keyMetadataRejectsCrossSlotAndStatefulCommandsBeforeSending() throws Exception {
        try (Peer peer = new Peer(); BobaStrawClusterClient client = builder(peer).build()) {
            assertThrows(IllegalArgumentException.class, () -> client.executeAsync("MGET", "a", "b"));
            assertThrows(IllegalArgumentException.class, () -> client.executeAsync("MSET", "a", "1", "b", "2"));
            assertThrows(IllegalArgumentException.class, () -> client.executeAsync("RENAME", "a", "b"));
            assertThrows(IllegalArgumentException.class, () -> client.executeAsync("EVAL", "return 1", "2", "a", "b"));
            assertThrows(IllegalArgumentException.class, () -> client.executeAsync("BLPOP", "a", "0"));
            assertThrows(IllegalArgumentException.class, () -> client.executeWithKeysAsync(new String[0], "ASKING"));
            assertThrows(IllegalArgumentException.class, () -> client.executeAsync("UNKNOWN", "key"));
            assertThrows(IllegalArgumentException.class, () -> client.executeWithKeysAsync(
                new String[] {"a"}, "MGET", "a", "b"));
            assertThrows(IllegalArgumentException.class, () -> client.executeWithKeysAsync(
                new String[0], "GET", "a"));
            assertEquals("tea", client.executeAsync("MGET", "{tag}:a", "{tag}:b")
                .toCompletableFuture().get(2, TimeUnit.SECONDS).asString());
            assertEquals("tea", client.executeWithKeysAsync(new String[] {"key"}, "UNKNOWN", "key")
                .toCompletableFuture().get(2, TimeUnit.SECONDS).asString());
            assertEquals(2, peer.appCommands.get());
        }
    }

    @Test
    void movedUpdatesSlotButOnlyAllowsOneRedirection() throws Exception {
        try (Peer source = new Peer(); Peer destination = new Peer();
             BobaStrawClusterClient client = builder(source).build()) {
            String key = "moved";
            source.redirect = "MOVED " + ClusterSlot.of(key) + " 127.0.0.1:" + destination.port();
            source.slots = Peer.slots(destination.port());
            assertEquals("tea", get(client, key));
            assertEquals("tea", get(client, key));
            assertEquals(1, source.appCommands.get());
            assertEquals(2, destination.appCommands.get());
            destination.redirect = "MOVED " + ClusterSlot.of(key) + " 127.0.0.1:" + source.port();
            Throwable error = failure(client.executeAsync("GET", key).toCompletableFuture());
            assertTrue(error instanceof BobaStrawServerException);
            assertTrue(error.getMessage().startsWith("MOVED "));
        }
    }

    @Test
    void askUsesDedicatedConnectionAndDoesNotReplaceSlotOwner() throws Exception {
        try (Peer source = new Peer(); Peer destination = new Peer();
             BobaStrawClusterClient client = builder(source).build()) {
            String key = "ask";
            source.redirect = "ASK " + ClusterSlot.of(key) + " 127.0.0.1:" + destination.port();
            for (int i = 0; i < 2; i++) {
                assertEquals("tea", get(client, key));
            }
            assertEquals(2, source.appCommands.get());
            assertEquals(2, destination.sessions.size());
            for (Session session : destination.sessions) {
                assertEquals(Arrays.asList("ASKING", "GET"), session.commands);
                assertTrue(session.closed.await(2, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void cancelWhileAskingClosesSocketAndNeverSendsTargetCommand() throws Exception {
        try (Peer source = new Peer(); Peer destination = new Peer();
             BobaStrawClusterClient client = builder(source).maxRedirectConnections(1).build()) {
            String key = "cancel-ask";
            source.redirect = "ASK " + ClusterSlot.of(key) + " 127.0.0.1:" + destination.port();
            destination.holdAsking = true;
            CompletableFuture<RespValue> result = client.executeAsync("GET", key).toCompletableFuture();
            assertTrue(destination.askReceived.await(2, TimeUnit.SECONDS));
            assertTrue(failure(client.executeAsync("GET", key).toCompletableFuture())
                instanceof BobaStrawBackpressureException);
            assertTrue(result.cancel(false));
            assertTrue(destination.sessions.get(0).closed.await(2, TimeUnit.SECONDS));
            assertEquals(0, destination.appCommands.get());
            client.close();
            assertThrows(BobaStrawConnectionException.class, () -> client.executeAsync("PING"));
        }
    }

    @Test
    void invalidRefreshKeepsOldMapAndPeriodicRefreshSwitchesOwner() throws Exception {
        try (Peer first = new Peer(); Peer second = new Peer();
             BobaStrawClusterClient client = builder(first)
                 .topologyRefreshInterval(Duration.ofMillis(100)).build()) {
            long version = client.topologyVersion();
            first.slots = "*0\r\n";
            assertTrue(failure(client.refreshTopology().toCompletableFuture()) instanceof BobaStrawConnectionException);
            assertEquals(version, client.topologyVersion());
            assertEquals("tea", get(client, "key"));
            first.slots = Peer.slots(second.port());
            await(() -> client.topologyVersion() > version);
            assertEquals("tea", get(client, "key"));
            assertEquals(1, second.appCommands.get());
        }
    }

    @Test
    void overlappingTopologyIsRejectedWithoutOpeningAdvertisedNodes() throws Exception {
        try (Peer peer = new Peer(); BobaStrawClusterClient client = builder(peer).build()) {
            String range = "*3\r\n:0\r\n:16383\r\n*2\r\n+127.0.0.1\r\n:" + peer.port() + "\r\n";
            peer.slots = "*2\r\n" + range + range;
            assertTrue(failure(client.refreshTopology().toCompletableFuture()) instanceof BobaStrawConnectionException);
            assertEquals(1, client.nodeMetrics().size());
            assertEquals("tea", get(client, "key"));
        }
    }

    @Test
    void reconnectRestoresNodeWithoutReplayingLostWrite() throws Exception {
        try (Peer peer = new Peer(); BobaStrawClusterClient client = builder(peer).build()) {
            peer.dropNext = true;
            Throwable error = failure(client.executeAsync("SET", "key", "value").toCompletableFuture());
            assertTrue(error instanceof BobaStrawCommandMayHaveExecutedException);
            await(() -> client.nodeMetrics().values().stream().anyMatch(m -> m.successfulReconnects() > 0));
            assertEquals(1, peer.appCommands.get());
            assertEquals("tea", get(client, "key"));
            assertEquals(2, peer.appCommands.get());
        }
    }

    @Test
    void failedBootstrapClosesSocketsButNotExternalResources() throws Exception {
        try (Peer peer = new Peer(); BobaStrawClientResources resources = BobaStrawClientResources.builder().build()) {
            peer.slots = "*0\r\n";
            assertThrows(BobaStrawConnectionException.class, () -> builder(peer).resources(resources).build());
            assertTrue(peer.sessions.get(0).closed.await(2, TimeUnit.SECONDS));
            assertTrue(resources.isOpen());
        }
    }

    @Test
    void refreshCallbacksNeverRunOnSelectorAndViewCancellationDoesNotStopRefresh() throws Exception {
        try (Peer peer = new Peer(); BobaStrawClusterClient client = builder(peer).build()) {
            AtomicReference<String> thread = new AtomicReference<String>();
            client.refreshTopology().thenRun(() -> thread.set(Thread.currentThread().getName()))
                .toCompletableFuture().get(2, TimeUnit.SECONDS);
            assertFalse(thread.get().startsWith("boba-straw-nio"));
            long version = client.topologyVersion();
            client.refreshTopology().toCompletableFuture().cancel(false);
            await(() -> client.topologyVersion() > version);
        }
    }

    @Test
    void malformedRedirectFailsWithoutConnectingOrReplaying() throws Exception {
        try (Peer peer = new Peer(); BobaStrawClusterClient client = builder(peer).build()) {
            peer.redirect = "MOVED 16384 127.0.0.1:1";
            assertTrue(failure(client.executeAsync("GET", "key").toCompletableFuture())
                instanceof BobaStrawServerException);
            assertEquals(1, peer.appCommands.get());
            assertEquals(1, client.nodeMetrics().size());
        }
    }

    @Test
    void fragmentedBlobErrorFailsOneRequestWithoutShiftingNextReply() throws Exception {
        try (Peer peer = new Peer(); BobaStrawClusterClient client = builder(peer).build()) {
            peer.blobErrorNext = true;
            CompletableFuture<RespValue> failed = client.executeAsync("GET", "a").toCompletableFuture();
            CompletableFuture<RespValue> following = client.executeAsync("GET", "b").toCompletableFuture();
            Throwable error = failure(failed);
            assertTrue(error instanceof BobaStrawServerException);
            assertEquals("ERR test", error.getMessage());
            assertEquals("tea", following.get(2, TimeUnit.SECONDS).asString());
            assertEquals(2, peer.appCommands.get());
        }
    }

    @Test
    void replacedNonSeedPrimaryIsClosedAndExternalResourcesRemainOwnedByCaller() throws Exception {
        try (Peer seed = new Peer(); Peer old = new Peer(); Peer replacement = new Peer();
             BobaStrawClientResources resources = BobaStrawClientResources.builder().build()) {
            seed.slots = Peer.slots(old.port());
            try (BobaStrawClusterClient client = builder(seed).resources(resources).build()) {
                assertEquals("tea", get(client, "key"));
                seed.slots = Peer.slots(replacement.port());
                old.slots = seed.slots;
                client.refreshTopology().toCompletableFuture().get(2, TimeUnit.SECONDS);
                assertTrue(old.sessions.get(0).closed.await(2, TimeUnit.SECONDS));
                assertFalse(client.nodeMetrics().containsKey("[127.0.0.1]:" + old.port()));
                assertTrue(client.nodeMetrics().containsKey("[127.0.0.1]:" + seed.port()));
                assertEquals("tea", get(client, "key"));
            }
            assertTrue(resources.isOpen());
            for (Session session : replacement.sessions) {
                assertTrue(session.closed.await(2, TimeUnit.SECONDS));
            }
        }
    }

    private static BobaStrawClusterClient.Builder builder(Peer peer) {
        return BobaStrawClusterClient.builder().seed("127.0.0.1", peer.port())
            .protocol(ProtocolVersion.RESP2).commandTimeout(Duration.ofSeconds(1))
            .reconnectInterval(Duration.ofMillis(30)).reconnectMaxInterval(Duration.ofMillis(120));
    }

    private static String get(BobaStrawClusterClient client, String key) throws Exception {
        return client.executeAsync("GET", key).toCompletableFuture().get(2, TimeUnit.SECONDS).asString();
    }

    private static Throwable failure(CompletableFuture<?> result) throws Exception {
        Throwable error = assertThrows(java.util.concurrent.ExecutionException.class,
            () -> result.get(3, TimeUnit.SECONDS));
        while (error instanceof java.util.concurrent.ExecutionException
            || error instanceof java.util.concurrent.CompletionException) {
            error = error.getCause();
        }
        return error;
    }

    private static void await(BooleanSupplier predicate) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!predicate.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(predicate.getAsBoolean());
    }

    private static final class Peer implements AutoCloseable {
        final ServerSocket server = new ServerSocket(0);
        final List<Session> sessions = new CopyOnWriteArrayList<Session>();
        final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        final AtomicInteger appCommands = new AtomicInteger();
        final CountDownLatch askReceived = new CountDownLatch(1);
        final Thread acceptor;
        volatile boolean closing;
        volatile boolean dropNext;
        volatile boolean holdAsking;
        volatile boolean blobErrorNext;
        volatile String redirect;
        volatile String slots;

        Peer() throws IOException {
            slots = slots(port());
            acceptor = new Thread(() -> {
                while (!closing) {
                    try {
                        Session session = new Session(this, server.accept());
                        sessions.add(session);
                        session.thread.start();
                    } catch (IOException error) {
                        if (!closing) {
                            failure.compareAndSet(null, error);
                        }
                    }
                }
            }, "cluster-test-acceptor");
            acceptor.setDaemon(true);
            acceptor.start();
        }

        int port() {
            return server.getLocalPort();
        }

        static String slots(int port) {
            return "*1\r\n*3\r\n:0\r\n:16383\r\n*2\r\n+127.0.0.1\r\n:" + port + "\r\n";
        }

        @Override
        public void close() throws Exception {
            closing = true;
            server.close();
            acceptor.join(2000);
            for (Session session : sessions) {
                session.socket.close();
                session.thread.join(2000);
            }
            assertNull(failure.get());
        }
    }

    private static final class Session {
        final Socket socket;
        final Thread thread;
        final List<String> commands = new CopyOnWriteArrayList<String>();
        final CountDownLatch closed = new CountDownLatch(1);

        Session(Peer peer, Socket socket) {
            this.socket = socket;
            thread = new Thread(() -> {
                try {
                    RespCodec.Decoder decoder = new RespCodec.Decoder();
                    byte[] bytes = new byte[4096];
                    int read;
                    while ((read = socket.getInputStream().read(bytes)) != -1) {
                        decoder.feed(bytes, read);
                        RespValue value;
                        while ((value = decoder.poll()) != null) {
                            List<RespValue> values = ((RespValue.Array) value).values;
                            String name = values.get(0).asString();
                            commands.add(name);
                            String reply;
                            if ("CLUSTER".equals(name)) {
                                reply = peer.slots;
                            } else if ("ASKING".equals(name)) {
                                peer.askReceived.countDown();
                                if (peer.holdAsking) {
                                    continue;
                                }
                                reply = "+OK\r\n";
                            } else {
                                peer.appCommands.incrementAndGet();
                                if (peer.dropNext) {
                                    peer.dropNext = false;
                                    return;
                                }
                                if (peer.blobErrorNext) {
                                    peer.blobErrorNext = false;
                                    for (byte fragment : "!8\r\nERR test\r\n".getBytes(StandardCharsets.UTF_8)) {
                                        socket.getOutputStream().write(fragment);
                                        socket.getOutputStream().flush();
                                    }
                                    continue;
                                }
                                reply = peer.redirect == null ? "+tea\r\n" : "-" + peer.redirect + "\r\n";
                            }
                            socket.getOutputStream().write(reply.getBytes(StandardCharsets.UTF_8));
                            socket.getOutputStream().flush();
                        }
                    }
                } catch (Throwable error) {
                    if (!peer.closing && !(error instanceof java.net.SocketException)) {
                        peer.failure.compareAndSet(null, error);
                    }
                } finally {
                    try {
                        socket.close();
                    } catch (IOException ignored) {
                        // Fixture owner may already have closed it.
                    }
                    closed.countDown();
                }
            }, "cluster-test-session");
            thread.setDaemon(true);
        }
    }
}
