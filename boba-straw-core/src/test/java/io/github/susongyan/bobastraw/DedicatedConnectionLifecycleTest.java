package io.github.susongyan.bobastraw;

import io.github.susongyan.bobastraw.protocol.RespCodec;
import io.github.susongyan.bobastraw.protocol.RespValue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

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
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@Tag("fault-injection")
class DedicatedConnectionLifecycleTest {
    @Test
    void idleTransactionConnectionIsReapedAndReplaced() throws Exception {
        try (Peer peer = new Peer(); BobaStrawClient client = BobaStrawClient.builder()
            .endpoint("127.0.0.1", peer.server.getLocalPort()).protocol(ProtocolVersion.RESP2)
            .transactionPoolMaxSize(1).transactionIdleTimeout(Duration.ofMillis(100)).build()) {
            client.sync().ping();
            client.transaction().discard().toCompletableFuture().get(2, TimeUnit.SECONDS);
            Session idle = peer.sessions.stream().filter(s -> s.commands.contains("UNWATCH"))
                .findFirst().get();
            assertTrue(idle.closed.await(2, TimeUnit.SECONDS));
            client.transaction().exec().toCompletableFuture().get(2, TimeUnit.SECONDS);
            assertEquals(3, peer.sessions.size());
        }
    }

    @Test
    void poolWaitHasBoundedTimeoutWithoutDamagingTheHeldLease() throws Exception {
        try (Peer peer = new Peer(); BobaStrawClient client = BobaStrawClient.builder()
            .endpoint("127.0.0.1", peer.server.getLocalPort()).protocol(ProtocolVersion.RESP2)
            .transactionPoolMaxSize(1).transactionAcquireTimeout(Duration.ofMillis(100)).build();
             BobaStrawTransaction transaction = client.transaction()) {
            assertThrows(IllegalStateException.class, client::transaction);
            transaction.exec().toCompletableFuture().get(2, TimeUnit.SECONDS);
            client.transaction().exec().toCompletableFuture().get(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void interruptingSynchronousBlockingCallClosesItsSocket() throws Exception {
        try (Peer peer = new Peer(); BobaStrawClient client = client(peer, 2000)) {
            peer.hold = "BLPOP";
            AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
            java.util.concurrent.atomic.AtomicBoolean interrupted = new java.util.concurrent.atomic.AtomicBoolean();
            Thread caller = new Thread(() -> {
                try {
                    client.sync().blpop(0, "queue");
                } catch (Throwable error) {
                    failure.set(error);
                    interrupted.set(Thread.currentThread().isInterrupted());
                }
            });
            caller.start();
            try {
                Session held = peer.awaitHeld();
                caller.interrupt();
                caller.join(2000);
                assertFalse(caller.isAlive());
                assertTrue(failure.get() instanceof BobaStrawConnectionException);
                assertTrue(interrupted.get());
                assertTrue(held.closed.await(1, TimeUnit.SECONDS));
                assertEquals("PONG", client.sync().ping());
            } finally {
                caller.interrupt();
                caller.join(2000);
            }
        }
    }

    @Test
    void disconnectAfterExecIsAmbiguousAndNeverReplayed() throws Exception {
        try (Peer peer = new Peer(); BobaStrawClient client = client(peer, 2000)) {
            peer.disconnect = "EXEC";
            Throwable error = failure(client.transaction().command("SET", "key", "value")
                .exec().toCompletableFuture());
            assertTrue(error instanceof BobaStrawCommandMayHaveExecutedException);
            assertEquals(1, peer.count("EXEC"));
            peer.disconnect = null;
            client.transaction().exec().toCompletableFuture().get(2, TimeUnit.SECONDS);
            assertEquals(3, peer.sessions.size());
        }
    }
    @Test
    void transactionPoolIsLazyAndSuccessfulLeasesAreReused() throws Exception {
        try (Peer peer = new Peer(); BobaStrawClient client = client(peer, 2000)) {
            assertEquals("PONG", client.sync().ping());
            assertEquals(1, peer.sessions.size());
            for (int i = 0; i < 3; i++) {
                try (BobaStrawTransaction transaction = client.transaction()) {
                    assertEquals(1, transaction.command("GET", "key").exec()
                        .toCompletableFuture().get(2, TimeUnit.SECONDS).size());
                }
            }
            assertEquals(2, peer.sessions.size());
            assertEquals(3, peer.count("EXEC"));
        }
    }

    @Test
    void waitingBorrowerCanProceedAfterReleaseOrDestruction() throws Exception {
        try (Peer peer = new Peer(); BobaStrawClient client = client(peer, 2000)) {
            for (boolean destroy : new boolean[] {false, true}) {
                BobaStrawTransaction first = client.transaction();
                CountDownLatch waiting = new CountDownLatch(1);
                CompletableFuture<BobaStrawTransaction> second = CompletableFuture.supplyAsync(() -> {
                    waiting.countDown();
                    return client.transaction();
                });
                assertTrue(waiting.await(1, TimeUnit.SECONDS));
                assertThrows(TimeoutException.class, () -> second.get(100, TimeUnit.MILLISECONDS));
                if (destroy) {
                    first.close();
                } else {
                    assertEquals("OK", first.discard().toCompletableFuture()
                        .get(2, TimeUnit.SECONDS).asString());
                }
                second.get(1, TimeUnit.SECONDS).close();
            }
            assertEquals(0, peer.count("DISCARD"));
        }
    }

    @Test
    void clientCloseWakesPoolWaiterInsteadOfCreatingAnotherLease() throws Exception {
        try (Peer peer = new Peer(); BobaStrawClient client = client(peer, 2000)) {
            client.transaction();
            CompletableFuture<BobaStrawTransaction> waiting =
                CompletableFuture.supplyAsync(client::transaction);
            assertThrows(TimeoutException.class, () -> waiting.get(100, TimeUnit.MILLISECONDS));
            client.close();
            assertThrows(java.util.concurrent.ExecutionException.class,
                () -> waiting.get(1, TimeUnit.SECONDS));
            assertThrows(BobaStrawConnectionException.class, client::transaction);
        }
    }

    @Test
    void cancellingExecDestroysLeaseAndCannotReturnItTwice() throws Exception {
        cancellingExec(false);
    }

    @Test
    void cancellingTypedExecDestroysLeaseAndCannotReturnItTwice() throws Exception {
        cancellingExec(true);
    }

    private void cancellingExec(boolean typed) throws Exception {
        try (Peer peer = new Peer(); BobaStrawClient client = client(peer, 2000)) {
            peer.hold = "EXEC";
            BobaStrawTransaction transaction = client.transaction().command("GET", "key");
            if (typed) {
                transaction.typed().eval("return 1", ScriptOutput.integer(), new String[0]);
            }
            CompletableFuture<?> result = typed ? transaction.execTyped().toCompletableFuture()
                : transaction.exec().toCompletableFuture();
            Session held = peer.awaitHeld();
            assertTrue(result.cancel(false));
            assertTrue(held.closed.await(1, TimeUnit.SECONDS));
            transaction.close();
            peer.hold = null;
            try (BobaStrawTransaction next = client.transaction()) {
                next.exec().toCompletableFuture().get(2, TimeUnit.SECONDS);
                assertEquals(3, peer.sessions.size());
            }
        }
    }

    @Test
    void watchCancellationAndAbandonedCloseDestroyLease() throws Exception {
        try (Peer peer = new Peer(); BobaStrawClient client = client(peer, 2000)) {
            peer.hold = "WATCH";
            BobaStrawTransaction transaction = client.transaction();
            CompletableFuture<RespValue> watch = transaction.watch("key").toCompletableFuture();
            Session held = peer.awaitHeld();
            assertThrows(IllegalStateException.class, transaction::exec);
            transaction.close();
            assertTrue(watch.isCancelled());
            assertTrue(held.closed.await(1, TimeUnit.SECONDS));
            peer.hold = null;
            try (BobaStrawTransaction next = client.transaction()) {
                next.watch("key").toCompletableFuture().get(2, TimeUnit.SECONDS);
                next.unwatch().toCompletableFuture().get(2, TimeUnit.SECONDS);
                next.discard().toCompletableFuture().get(2, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void failedMultiOrQueueNeverSendsExecAndDestroysLease() throws Exception {
        for (String rejected : new String[] {"MULTI", "GET"}) {
            try (Peer peer = new Peer(); BobaStrawClient client = client(peer, 2000)) {
                peer.reject = rejected;
                try (BobaStrawTransaction transaction = client.transaction()) {
                    assertThrows(java.util.concurrent.ExecutionException.class,
                        () -> transaction.command("GET", "key").exec().toCompletableFuture()
                            .get(2, TimeUnit.SECONDS));
                }
                assertEquals(0, peer.count("EXEC"));
                if ("MULTI".equals(rejected)) {
                    assertEquals(0, peer.count("GET"));
                }
                peer.reject = null;
                client.transaction().exec().toCompletableFuture().get(2, TimeUnit.SECONDS);
                assertEquals(3, peer.sessions.size());
            }
        }
    }

    @Test
    void execTimeoutIsAmbiguousAndClosesDedicatedSocket() throws Exception {
        execTimeout(false);
    }

    @Test
    void typedExecTimeoutIsAmbiguousAndClosesDedicatedSocket() throws Exception {
        execTimeout(true);
    }

    private void execTimeout(boolean typed) throws Exception {
        try (Peer peer = new Peer(); BobaStrawClient client = client(peer, 250)) {
            peer.hold = "EXEC";
            BobaStrawTransaction transaction = client.transaction();
            if (typed) {
                transaction.typed().eval("return 1", ScriptOutput.integer(), new String[0]);
            }
            CompletableFuture<?> result = typed ? transaction.execTyped().toCompletableFuture()
                : transaction.exec().toCompletableFuture();
            Session held = peer.awaitHeld();
            Throwable failure = failure(result);
            assertTrue(failure instanceof BobaStrawCommandTimeoutException);
            assertTrue(((BobaStrawCommandTimeoutException) failure).mayHaveExecuted());
            assertTrue(held.closed.await(1, TimeUnit.SECONDS));
            assertEquals(1, peer.count("EXEC"));
        }
    }

    @Test
    void typedPipelineTimeoutIsNotConvertedToAnIndividualServerError() throws Exception {
        try (Peer peer = new Peer(); BobaStrawClient client = client(peer, 250)) {
            peer.hold = "GET";
            BobaStrawPipeline pipeline = client.pipeline();
            pipeline.typed().get("key");
            CompletableFuture<BobaStrawBatchResult> result = pipeline.executeTyped().toCompletableFuture();
            peer.awaitHeld();
            Throwable error = failure(result);
            assertTrue(error instanceof BobaStrawCommandTimeoutException);
            assertTrue(((BobaStrawCommandTimeoutException) error).mayHaveExecuted());
            assertEquals(1, peer.count("GET"));
        }
    }

    @Test
    void blockingCancellationReleasesCapacityWithoutBlockingSharedPing() throws Exception {
        try (Peer peer = new Peer(); BobaStrawClient client = client(peer, 2000)) {
            peer.hold = "BLPOP";
            CompletableFuture<List<String>> result = client.async().blpop(0, "queue").toCompletableFuture();
            Session held = peer.awaitHeld();
            assertEquals("PONG", client.sync().ping());
            assertTrue(failure(client.async().blpop(0, "other").toCompletableFuture())
                instanceof BobaStrawBackpressureException);
            assertTrue(result.cancel(false));
            assertTrue(held.closed.await(1, TimeUnit.SECONDS));
            // A shared ping acts as a loop barrier after the socket cleanup.
            assertEquals("PONG", client.sync().ping());
            assertEquals(Arrays.asList("queue", "tea"), client.sync().brpop(1, "queue"));
            assertEquals(1, peer.count("BLPOP"));
        }
    }

    @Test
    void blockingTimeoutAndClientCloseReleaseSocket() throws Exception {
        try (Peer peer = new Peer(); BobaStrawClient client = client(peer, 250)) {
            peer.hold = "BLPOP";
            CompletableFuture<List<String>> result = client.async().blpop(0, "queue").toCompletableFuture();
            Session held = peer.awaitHeld();
            Throwable failure = failure(result);
            assertTrue(failure instanceof BobaStrawCommandTimeoutException);
            assertTrue(((BobaStrawCommandTimeoutException) failure).mayHaveExecuted());
            assertTrue(held.closed.await(1, TimeUnit.SECONDS));
            assertEquals("PONG", client.sync().ping());
        }
        try (Peer peer = new Peer(); BobaStrawClient client = client(peer, 2000)) {
            peer.hold = "BLPOP";
            CompletableFuture<List<String>> result = client.async().blpop(0, "queue").toCompletableFuture();
            Session held = peer.awaitHeld();
            client.close();
            assertTrue(held.closed.await(1, TimeUnit.SECONDS));
            assertTrue(failure(result) instanceof BobaStrawConnectionException);
        }
    }

    @Test
    void blockingSocketClosesEvenWhenCallbackWorkerIsBusy() throws Exception {
        try (Peer peer = new Peer(); BobaStrawClient client = client(peer, 250)) {
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            CompletableFuture<Void> busy = client.async().ping().thenRun(() -> {
                entered.countDown();
                try {
                    release.await(3, TimeUnit.SECONDS);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                }
            }).toCompletableFuture();
            try {
                assertTrue(entered.await(1, TimeUnit.SECONDS));
                peer.hold = "BLPOP";
                CompletableFuture<List<String>> pop = client.async().blpop(0, "queue").toCompletableFuture();
                Session held = peer.awaitHeld();
                assertTrue(held.closed.await(1, TimeUnit.SECONDS));
                assertEquals(Arrays.asList("queue", "tea"), client.sync().brpop(1, "queue"));
                release.countDown();
                assertTrue(failure(pop) instanceof BobaStrawCommandTimeoutException);
            } finally {
                release.countDown();
                busy.get(2, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void validatesStateCommandsAndBlockingArgumentsBeforeWriting() throws Exception {
        try (Peer peer = new Peer(); BobaStrawClient client = client(peer, 2000);
             BobaStrawTransaction transaction = client.transaction()) {
            for (String command : new String[] {"DISCARD", "EXEC", "SELECT", "subscribe"}) {
                assertThrows(IllegalArgumentException.class, () -> transaction.command(command));
            }
            assertThrows(IllegalArgumentException.class, () -> client.async().blpop(-1, "key"));
            assertThrows(IllegalArgumentException.class, () -> client.sync().brpop(0));
            assertEquals(0, peer.count("BLPOP"));
        }
    }

    @Test
    void transactionTimeoutReleasesPoolSlotBeforeBusyCallbackWorkerResumes() throws Exception {
        try (Peer peer = new Peer(); BobaStrawClient client = client(peer, 250)) {
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            CompletableFuture<Void> busy = client.async().ping().thenRun(() -> {
                entered.countDown();
                try {
                    release.await(3, TimeUnit.SECONDS);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                }
            }).toCompletableFuture();
            try {
                assertTrue(entered.await(1, TimeUnit.SECONDS));
                peer.hold = "WATCH";
                CompletableFuture<RespValue> watch = client.transaction().watch("key").toCompletableFuture();
                assertTrue(peer.awaitHeld().closed.await(1, TimeUnit.SECONDS));
                try (BobaStrawTransaction next = client.transaction()) {
                    // A new lease is available before the failed operation can notify its caller.
                    assertFalse(watch.isDone());
                }
                release.countDown();
                assertTrue(failure(watch) instanceof BobaStrawCommandTimeoutException);
            } finally {
                release.countDown();
                busy.get(2, TimeUnit.SECONDS);
            }
        }
    }

    private static BobaStrawClient client(Peer peer, long timeoutMillis) {
        return BobaStrawClient.builder().endpoint("127.0.0.1", peer.server.getLocalPort())
            .protocol(ProtocolVersion.RESP2).commandTimeout(Duration.ofMillis(timeoutMillis))
            .transactionPoolMaxSize(1).transactionAcquireTimeout(Duration.ofSeconds(2))
            .maxBlockingConnections(1).build();
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

    private static final class Peer implements AutoCloseable {
        final ServerSocket server = new ServerSocket(0);
        final List<Session> sessions = new CopyOnWriteArrayList<Session>();
        final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        final CountDownLatch held = new CountDownLatch(1);
        volatile Session heldSession;
        volatile String hold;
        volatile String reject;
        volatile String disconnect;
        volatile boolean closing;
        final Thread acceptor;

        Peer() throws IOException {
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
            }, "dedicated-test-acceptor");
            acceptor.setDaemon(true);
            acceptor.start();
        }

        int count(String command) {
            int count = 0;
            for (Session session : sessions) {
                for (String received : session.commands) {
                    if (command.equals(received)) {
                        count++;
                    }
                }
            }
            return count;
        }

        Session awaitHeld() throws InterruptedException {
            assertTrue(held.await(2, TimeUnit.SECONDS));
            return heldSession;
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
            assertNull(failure.get(), "test server failure");
        }
    }

    private static final class Session {
        final Socket socket;
        final Thread thread;
        final CountDownLatch closed = new CountDownLatch(1);
        final List<String> commands = new CopyOnWriteArrayList<String>();
        int queued;
        boolean multi;

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
                            List<RespValue> args = ((RespValue.Array) value).values;
                            String command = args.get(0).asString();
                            commands.add(command);
                            if (command.equals(peer.disconnect)) {
                                socket.close();
                                return;
                            }
                            if (command.equals(peer.hold)) {
                                peer.heldSession = this;
                                peer.held.countDown();
                                continue;
                            }
                            String reply;
                            if (command.equals(peer.reject)) {
                                reply = "-ERR rejected\r\n";
                            } else if ("MULTI".equals(command)) {
                                multi = true;
                                queued = 0;
                                reply = "+OK\r\n";
                            } else if ("EXEC".equals(command)) {
                                multi = false;
                                StringBuilder response = new StringBuilder("*" + queued + "\r\n");
                                for (int i = 0; i < queued; i++) {
                                    response.append("+tea\r\n");
                                }
                                reply = response.toString();
                            } else if (multi) {
                                queued++;
                                reply = "+QUEUED\r\n";
                            } else if ("PING".equals(command)) {
                                reply = "+PONG\r\n";
                            } else if ("BRPOP".equals(command) || "BLPOP".equals(command)) {
                                reply = "*2\r\n+queue\r\n+tea\r\n";
                            } else {
                                reply = "+OK\r\n";
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
                    closed.countDown();
                    try {
                        socket.close();
                    } catch (IOException ignored) {
                        // Already closed by the fixture owner.
                    }
                }
            }, "dedicated-test-session");
            thread.setDaemon(true);
        }
    }
}
