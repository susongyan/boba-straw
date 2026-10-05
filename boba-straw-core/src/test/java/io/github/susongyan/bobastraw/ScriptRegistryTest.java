package io.github.susongyan.bobastraw;

import io.github.susongyan.bobastraw.protocol.RespCodec;
import io.github.susongyan.bobastraw.protocol.RespValue;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ScriptRegistryTest {
    private static final String[] NO_KEYS = new String[0];

    @Test
    void testServerOwnsTheExactClientAddressWithoutPortReuse() throws Exception {
        try (ServerSocket server = LoopbackTestServer.open(); ServerSocket collision = new ServerSocket()) {
            assertEquals("127.0.0.1", server.getInetAddress().getHostAddress());
            assertFalse(server.getReuseAddress());
            collision.setReuseAddress(true);
            assertThrows(IOException.class, () -> collision.bind(
                new java.net.InetSocketAddress("127.0.0.1", server.getLocalPort())));
        }
    }

    @Test
    void cancellationAfterRecoverySubmissionKeepsTheReplyPlaceholder() throws Exception {
        try (Peer peer = new Peer(); BobaStrawClient client = peer.client(2000)) {
            client.scripts().register("one", "return 1", ScriptOutput.integer());
            CompletableFuture<Long> first = call(client);
            peer.next("EVAL").reply(":1\r\n");
            first.get(3, TimeUnit.SECONDS);
            CompletableFuture<Long> next = call(client);
            peer.next("EVALSHA").reply("-NOSCRIPT missing\r\n");
            Request recovery = peer.next("EVAL");
            assertTrue(next.cancel(false));
            CompletableFuture<RespValue> ping = client.executeAsync("PING").toCompletableFuture();
            Request pingRequest = peer.next("PING");
            recovery.reply(":2\r\n");
            pingRequest.reply("+PONG\r\n");
            assertEquals("PONG", ping.get(3, TimeUnit.SECONDS).asString());
            assertNull(peer.requests.poll(100, TimeUnit.MILLISECONDS));
        }
    }

    @Test
    void askingTimeoutDoesNotClaimTheBusinessScriptWasSent() throws Exception {
        try (Peer peer = new Peer(); BobaStrawClient client = peer.client(2000)) {
            BobaStrawScripts scripts = new BobaStrawScripts(keys -> new BobaStrawScripts.Target(
                client.scriptTarget().connection, () -> true, true), Duration.ofMillis(300), false);
            scripts.register("one", "return 1", ScriptOutput.integer());
            CompletableFuture<Long> result = scripts.execute("one", ScriptOutput.integer(), NO_KEYS).toCompletableFuture();
            peer.next("ASKING");
            Throwable error = failure(result);
            assertTrue(error instanceof BobaStrawCommandTimeoutException);
            assertFalse(((BobaStrawCommandTimeoutException) error).mayHaveExecuted());
            assertNull(peer.requests.poll(100, TimeUnit.MILLISECONDS));
            scripts.close();
        }
    }

    @Test
    void concurrentConflictingRegistrationHasExactlyOneWinner() throws Exception {
        BobaStrawScripts scripts = new BobaStrawScripts(keys -> {
            throw new AssertionError("Registration must not contact Redis");
        }, Duration.ofSeconds(1), false);
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger accepted = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger rejected = new java.util.concurrent.atomic.AtomicInteger();
        List<Thread> threads = new ArrayList<Thread>();
        for (String body : new String[] {"return 1", "return 2"}) {
            Thread thread = new Thread(() -> {
                try {
                    start.await();
                    scripts.register("one", body, ScriptOutput.integer());
                    accepted.incrementAndGet();
                } catch (IllegalArgumentException conflict) {
                    rejected.incrementAndGet();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            });
            threads.add(thread);
            thread.start();
        }
        start.countDown();
        for (Thread thread : threads) {
            thread.join(2000);
            assertFalse(thread.isAlive());
        }
        assertEquals(1, accepted.get());
        assertEquals(1, rejected.get());
        scripts.close();
    }

    @Test
    void movedReevaluatesDestinationHintAndRecoveryStaysOnThatTarget() throws Exception {
        try (Peer first = new Peer(); Peer second = new Peer();
             BobaStrawClient a = first.client(2000); BobaStrawClient b = second.client(2000)) {
            BobaStrawScripts scripts = new BobaStrawScripts(new BobaStrawScripts.Router() {
                @Override
                public BobaStrawScripts.Target select(byte[][] keys) {
                    return a.scriptTarget();
                }

                @Override
                public BobaStrawScripts.Target redirect(BobaStrawScripts.Target source,
                                                        byte[][] keys, Throwable error) {
                    return error.getMessage().startsWith("MOVED ") ? b.scriptTarget() : null;
                }
            }, Duration.ofSeconds(2), false);
            scripts.register("one", "return 1", ScriptOutput.integer());
            for (int index = 0; index < 2; index++) {
                CompletableFuture<Long> result = scripts.execute("one", ScriptOutput.integer(), NO_KEYS).toCompletableFuture();
                first.next("EVAL").reply("-MOVED 1 destination\r\n");
                Request target = second.next(index == 0 ? "EVAL" : "EVALSHA");
                if (index == 1) {
                    target.reply("-NOSCRIPT missing\r\n");
                    target = second.next("EVAL");
                }
                target.reply(":1\r\n");
                assertEquals(Long.valueOf(1), result.get(3, TimeUnit.SECONDS));
            }
            scripts.close();
        }
    }

    @Test
    void oldTargetLateSuccessCannotInstallCurrentHint() throws Exception {
        try (Peer peer = new Peer(); BobaStrawClient client = peer.client(2000)) {
            java.util.concurrent.atomic.AtomicBoolean current = new java.util.concurrent.atomic.AtomicBoolean(true);
            BobaStrawScripts scripts = new BobaStrawScripts(keys -> new BobaStrawScripts.Target(
                client.scriptTarget().connection, current::get, false), Duration.ofSeconds(2), false);
            scripts.register("one", "return 1", ScriptOutput.integer());
            CompletableFuture<Long> first = scripts.execute("one", ScriptOutput.integer(), NO_KEYS).toCompletableFuture();
            Request old = peer.next("EVAL");
            current.set(false);
            old.reply(":1\r\n");
            first.get(3, TimeUnit.SECONDS);
            current.set(true);
            CompletableFuture<Long> next = scripts.execute("one", ScriptOutput.integer(), NO_KEYS).toCompletableFuture();
            peer.next("EVAL").reply(":2\r\n");
            assertEquals(Long.valueOf(2), next.get(3, TimeUnit.SECONDS));
            scripts.close();
        }
    }

    @Test
    void closeCancelsActiveExecutionAndBodyByteLimitRejectsLocally() throws Exception {
        try (Peer peer = new Peer(); BobaStrawClient client = peer.client(2000)) {
            BobaStrawScripts limited = new BobaStrawScripts(keys -> client.scriptTarget(),
                Duration.ofSeconds(2), true, 10, 7, 1);
            assertThrows(BobaStrawBackpressureException.class,
                () -> limited.register("one", "return 1", ScriptOutput.integer()));
            limited.close();
            client.scripts().register("one", "return 1", ScriptOutput.integer());
            CompletableFuture<Long> result = call(client);
            peer.next("EVAL");
            client.close();
            assertTrue(result.isCancelled());
            assertThrows(BobaStrawConnectionException.class, () -> call(client));
        }
    }

    @Test
    void firstEvalThenShaAndSingleNoScriptRecovery() throws Exception {
        try (Peer peer = new Peer(); BobaStrawClient client = peer.client(2000)) {
            BobaStrawScripts scripts = client.scripts();
            scripts.register("one", "return 1", ScriptOutput.integer());
            scripts.register("one", "return 1", ScriptOutput.integer());
            assertThrows(IllegalArgumentException.class,
                () -> scripts.register("one", "return 2", ScriptOutput.integer()));
            assertThrows(IllegalArgumentException.class,
                () -> scripts.register("one", "return 1", ScriptOutput.string()));
            assertThrows(IllegalArgumentException.class,
                () -> scripts.execute("missing", ScriptOutput.integer(), NO_KEYS));
            assertThrows(IllegalArgumentException.class,
                () -> scripts.execute("one", ScriptOutput.string(), NO_KEYS));
            CompletableFuture<Long> first = scripts.execute("one", ScriptOutput.integer(), NO_KEYS).toCompletableFuture();
            Request request = peer.next("EVAL");
            assertEquals("return 1", request.text(1));
            request.reply(":1\r\n");
            assertEquals(Long.valueOf(1), first.get(3, TimeUnit.SECONDS));
            CompletableFuture<Long> second = scripts.execute("one", ScriptOutput.integer(), NO_KEYS).toCompletableFuture();
            request = peer.next("EVALSHA");
            assertEquals("e0e1f9fabfc9d4800c877a703b823ac0578ff8db", request.text(1));
            request.reply("-NOSCRIPT No matching script\r\n");
            peer.next("EVAL").reply(":2\r\n");
            assertEquals(Long.valueOf(2), second.get(3, TimeUnit.SECONDS));
            CompletableFuture<Long> third = scripts.execute("one", ScriptOutput.integer(), NO_KEYS).toCompletableFuture();
            peer.next("EVALSHA").reply("-NOSCRIPT missing\r\n");
            peer.next("EVAL").reply("-NOSCRIPT business error\r\n");
            assertTrue(failure(third) instanceof BobaStrawServerException);
            assertNull(peer.requests.poll(100, TimeUnit.MILLISECONDS));
        }
    }

    @Test
    void decodeFailureStillRecordsHintAndOtherErrorsNeverReplay() throws Exception {
        try (Peer peer = new Peer(); BobaStrawClient client = peer.client(2000)) {
            client.scripts().register("one", "return 1", ScriptOutput.integer());
            CompletableFuture<Long> first = call(client);
            peer.next("EVAL").reply("+wrong type\r\n");
            assertTrue(failure(first) instanceof IllegalStateException);
            for (String error : new String[] {"ERR contains NOSCRIPT", "NOSCRIPTING bad", "BUSY busy", "NOPERM denied"}) {
                CompletableFuture<Long> result = call(client);
                peer.next("EVALSHA").reply("-" + error + "\r\n");
                assertEquals(error, failure(result).getMessage());
                assertNull(peer.requests.poll(30, TimeUnit.MILLISECONDS));
            }
        }
    }

    @Test
    void binaryInputsAreSnapshotsAndNullSuccessIsCached() throws Exception {
        int repetitions = Integer.getInteger("boba.straw.scriptSnapshotRepetitions", 1);
        assertTrue(repetitions > 0 && repetitions <= 1000);
        for (int iteration = 0; iteration < repetitions; iteration++) {
            verifyBinarySnapshot();
        }
    }

    private void verifyBinarySnapshot() throws Exception {
        try (Peer peer = new Peer(); BobaStrawClient client = peer.client(2000)) {
            BinaryFailureEvidence evidence = new BinaryFailureEvidence(client);
            byte[] body = "return ARGV[1]".getBytes(StandardCharsets.UTF_8);
            client.scripts().register("binary", body, ScriptOutput.bytes());
            body[0] = 0;
            byte[] key = {(byte) 0xff, 0};
            byte[] value = {(byte) 0xfe, 0};
            CompletableFuture<byte[]> result = client.scripts().executeBinary("binary", ScriptOutput.bytes(),
                new byte[][] {key}, value).toCompletableFuture();
            key[0] = 0;
            value[0] = 0;
            Request request = peer.next("EVAL", result, client, evidence);
            assertEquals("return ARGV[1]", request.text(1));
            assertArrayEquals(new byte[] {(byte) 0xff, 0}, request.bytes(3));
            assertArrayEquals(new byte[] {(byte) 0xfe, 0}, request.bytes(4));
            request.reply("$-1\r\n");
            assertNull(result.get(3, TimeUnit.SECONDS));
            CompletableFuture<byte[]> next = client.scripts().executeBinary("binary", ScriptOutput.bytes(),
                new byte[0][]).toCompletableFuture();
            peer.next("EVALSHA").reply("$0\r\n\r\n");
            assertArrayEquals(new byte[0], next.get(3, TimeUnit.SECONDS));
        }
    }

    @Test
    void concurrentFirstCallsAreNotCoalescedAndCancellationDrains() throws Exception {
        try (Peer peer = new Peer(); BobaStrawClient client = peer.client(2000)) {
            client.scripts().register("one", "return 1", ScriptOutput.integer());
            CompletableFuture<Long> first = call(client);
            CompletableFuture<Long> second = call(client);
            Request a = peer.next("EVAL");
            Request b = peer.next("EVAL");
            assertTrue(first.cancel(false));
            a.reply(":1\r\n");
            b.reply(":2\r\n");
            assertEquals(Long.valueOf(2), second.get(3, TimeUnit.SECONDS));
            CompletableFuture<Long> cancelled = call(client);
            Request c = peer.next("EVALSHA");
            assertTrue(cancelled.cancel(false));
            c.reply("-NOSCRIPT missing\r\n");
            CompletableFuture<RespValue> ping = client.executeAsync("PING").toCompletableFuture();
            peer.next("PING").reply("+PONG\r\n");
            assertEquals("PONG", ping.get(3, TimeUnit.SECONDS).asString());
            assertNull(peer.requests.poll(100, TimeUnit.MILLISECONDS));
        }
    }

    @Test
    void recoveryUsesOriginalDeadlineAndReportsWrittenTimeout() throws Exception {
        try (Peer peer = new Peer(); BobaStrawClient client = peer.client(1000)) {
            client.scripts().register("one", "return 1", ScriptOutput.integer());
            CompletableFuture<Long> first = call(client);
            peer.next("EVAL").reply(":1\r\n");
            first.get(3, TimeUnit.SECONDS);
            long start = System.nanoTime();
            CompletableFuture<Long> next = call(client);
            Request cached = peer.next("EVALSHA");
            Thread.sleep(700);
            cached.reply("-NOSCRIPT missing\r\n");
            Request recovery = peer.next("EVAL");
            Throwable error = failure(next);
            assertTrue(error instanceof BobaStrawCommandTimeoutException);
            assertTrue(((BobaStrawCommandTimeoutException) error).mayHaveExecuted());
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 1500,
                "Recovery must not get a fresh 1000 ms timeout");
            recovery.reply(":2\r\n");
            CompletableFuture<RespValue> ping = client.executeAsync("PING").toCompletableFuture();
            peer.next("PING").reply("+PONG\r\n");
            assertEquals("PONG", ping.get(3, TimeUnit.SECONDS).asString());
        }
    }

    @Test
    void disconnectIsNotRetriedAndNewConnectionStartsWithEval() throws Exception {
        try (Peer peer = new Peer(); BobaStrawClient client = peer.client(2000)) {
            client.scripts().register("one", "return 1", ScriptOutput.integer());
            CompletableFuture<Long> first = call(client);
            peer.next("EVAL").reply(":1\r\n");
            first.get(3, TimeUnit.SECONDS);
            CompletableFuture<Long> second = call(client);
            peer.next("EVALSHA").socket.close();
            assertTrue(failure(second) instanceof BobaStrawCommandMayHaveExecutedException);
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (client.successfulReconnects() == 0 && System.nanoTime() < end) {
                Thread.sleep(5);
            }
            assertTrue(client.successfulReconnects() > 0);
            assertNull(peer.requests.poll(50, TimeUnit.MILLISECONDS));
            CompletableFuture<Long> third = call(client);
            peer.next("EVAL").reply(":3\r\n");
            assertEquals(Long.valueOf(3), third.get(3, TimeUnit.SECONDS));
        }
    }

    @Test
    void boundedDefinitionsAndHintsAndCloseCleanup() throws Exception {
        try (Peer peer = new Peer(); BobaStrawClient client = peer.client(2000)) {
            BobaStrawScripts scripts = new BobaStrawScripts(keys -> client.scriptTarget(),
                Duration.ofSeconds(2), true, BobaStrawScriptOptions.builder()
                    .maxRegisteredScripts(2).maxScriptBytes(16).maxCacheHints(1).build());
            scripts.register("a", "return 1", ScriptOutput.integer());
            scripts.register("b", "return 2", ScriptOutput.integer());
            assertThrows(BobaStrawBackpressureException.class,
                () -> scripts.register("c", "", ScriptOutput.integer()));
            for (String name : new String[] {"a", "b", "a"}) {
                CompletableFuture<Long> result = scripts.execute(name, ScriptOutput.integer(), NO_KEYS).toCompletableFuture();
                peer.next("EVAL").reply(":1\r\n");
                result.get(3, TimeUnit.SECONDS);
            }
            CompletableFuture<Long> active = scripts.execute("a", ScriptOutput.integer(), NO_KEYS).toCompletableFuture();
            Request request = peer.next("EVALSHA");
            scripts.close();
            assertTrue(active.isCancelled());
            assertThrows(BobaStrawConnectionException.class,
                () -> scripts.register("a", "return 1", ScriptOutput.integer()));
            request.reply("-NOSCRIPT missing\r\n");
            assertNull(peer.requests.poll(100, TimeUnit.MILLISECONDS));
        }
    }

    @Test
    void configuredAdmissionReleasesOnCancellationAndCompletion() throws Exception {
        try (Peer peer = new Peer(); BobaStrawClient client = BobaStrawClient.builder()
            .endpoint("127.0.0.1", peer.listener.getLocalPort()).protocol(ProtocolVersion.RESP2)
            .commandTimeout(Duration.ofSeconds(5))
            .scriptOptions(BobaStrawScriptOptions.builder().maxRegisteredScripts(1)
                .maxScriptBytes(8).maxCacheHints(1).maxInFlightExecutions(1).build()).build()) {
            client.scripts().register("one", "return 1", ScriptOutput.integer());
            client.scripts().register("one", "return 1", ScriptOutput.integer());
            assertThrows(BobaStrawBackpressureException.class,
                () -> client.scripts().register("two", "", ScriptOutput.integer()));
            CompletableFuture<Long> first = call(client);
            Request cancelled = peer.next("EVAL");
            assertThrows(BobaStrawBackpressureException.class, () -> call(client));
            assertTrue(first.cancel(false));
            CompletableFuture<Long> second = call(client);
            Request next = peer.next("EVAL");
            cancelled.reply(":99\r\n");
            next.reply(":2\r\n");
            assertEquals(Long.valueOf(2), second.get(3, TimeUnit.SECONDS));
            CompletableFuture<Long> third = call(client);
            peer.next("EVALSHA").reply(":3\r\n");
            assertEquals(Long.valueOf(3), third.get(3, TimeUnit.SECONDS));
        }
    }

    @Test
    void cancellingScriptPipelineDrainsRepliesWithoutNoscriptRecovery() throws Exception {
        try (Peer peer = new Peer(); BobaStrawClient client = peer.client(2000)) {
            client.scripts().register("one", "return 1", ScriptOutput.integer());
            BobaStrawPipeline batch = client.pipeline();
            batch.typed().script("one", ScriptOutput.integer(), NO_KEYS);
            batch.typed().evalSha("0000000000000000000000000000000000000000",
                ScriptOutput.integer(), NO_KEYS);
            CompletableFuture<BobaStrawBatchResult> result = batch.executeTyped().toCompletableFuture();
            Request first = peer.next("EVAL");
            Request second = peer.next("EVALSHA");
            assertTrue(result.cancel(false));
            CompletableFuture<Long> after = call(client);
            Request third = peer.next("EVAL");
            first.reply(":99\r\n");
            second.reply("-NOSCRIPT missing\r\n");
            third.reply(":3\r\n");
            assertEquals(Long.valueOf(3), after.get(3, TimeUnit.SECONDS));
            assertNull(peer.requests.poll(50, TimeUnit.MILLISECONDS));
        }
    }

    private static CompletableFuture<Long> call(BobaStrawClient client) {
        return client.scripts().execute("one", ScriptOutput.integer(), NO_KEYS).toCompletableFuture();
    }

    private static Throwable failure(CompletableFuture<?> result) throws Exception {
        return assertThrows(ExecutionException.class, () -> result.get(3, TimeUnit.SECONDS)).getCause();
    }

    private static final class Request {
        final Socket socket;
        final List<RespValue> values;

        Request(Socket socket, List<RespValue> values) {
            this.socket = socket;
            this.values = values;
        }

        String text(int index) {
            return values.get(index).asString();
        }

        byte[] bytes(int index) {
            return ((RespValue.BlobString) values.get(index)).value;
        }

        void reply(String wire) throws IOException {
            socket.getOutputStream().write(wire.getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();
        }
    }

    private static final class Peer implements AutoCloseable {
        final ServerSocket listener = LoopbackTestServer.open();
        final BlockingQueue<Request> requests = new LinkedBlockingQueue<Request>();
        final List<Socket> sockets = new CopyOnWriteArrayList<Socket>();
        final List<Thread> workers = new CopyOnWriteArrayList<Thread>();
        final Thread acceptor;
        final java.util.concurrent.atomic.AtomicLong bytesRead = new java.util.concurrent.atomic.AtomicLong();
        final java.util.concurrent.atomic.AtomicReference<Throwable> readFailure =
            new java.util.concurrent.atomic.AtomicReference<Throwable>();
        volatile boolean closed;

        Peer() throws IOException {
            acceptor = new Thread(() -> {
                while (!closed) {
                    try {
                        Socket socket = listener.accept();
                        sockets.add(socket);
                        Thread worker = new Thread(() -> read(socket), "script-test-peer");
                        workers.add(worker);
                        worker.start();
                    } catch (IOException error) {
                        if (!closed) {
                            throw new IllegalStateException(error);
                        }
                    }
                }
            }, "script-test-acceptor");
            acceptor.start();
        }

        BobaStrawClient client(long timeoutMillis) {
            return BobaStrawClient.builder().endpoint("127.0.0.1", listener.getLocalPort())
                .protocol(ProtocolVersion.RESP2).commandTimeout(Duration.ofMillis(timeoutMillis))
                .reconnectInterval(Duration.ofMillis(30)).build();
        }

        Request next(String command) throws InterruptedException {
            Request result = requests.poll(3, TimeUnit.SECONDS);
            assertNotNull(result, "Expected " + command);
            assertEquals(command, result.text(0));
            return result;
        }

        Request next(String command, CompletableFuture<?> future, BobaStrawClient client,
                     BinaryFailureEvidence evidence)
            throws InterruptedException {
            Request request = requests.poll(3, TimeUnit.SECONDS);
            if (request == null) {
                BobaStrawClientMetrics metrics = client.metrics();
                String state = "pending";
                if (future.isDone()) {
                    try {
                        state = "completed: " + future.join();
                    } catch (RuntimeException error) {
                        java.io.StringWriter trace = new java.io.StringWriter();
                        error.printStackTrace(new java.io.PrintWriter(trace));
                        state = trace.toString();
                    }
                }
                fail("Expected " + command + "; result=" + state + "; connection="
                    + metrics.sharedConnectionState() + "; accepted=" + sockets.size()
                    + "; peerBytes=" + bytesRead.get() + "; written=" + metrics.socketBytesWritten()
                    + "; queued=" + metrics.queuedWriteBytes() + "; inFlight=" + metrics.inFlightCommands()
                    + "; peerFailure=" + readFailure.get() + "; listen=" + listener.getLocalSocketAddress()
                    + "; acceptor=" + acceptor.getState() + "; socket=" + socketAddresses(client)
                    + "; wire=" + evidence.snapshot());
            }
            assertEquals(command, request.text(0));
            return request;
        }

        private String socketAddresses(BobaStrawClient client) {
            try {
                java.lang.reflect.Field current = BobaStrawClient.class.getDeclaredField("connection");
                current.setAccessible(true);
                Object connection = current.get(client);
                java.lang.reflect.Field field = connection.getClass().getDeclaredField("channel");
                field.setAccessible(true);
                java.nio.channels.SocketChannel channel = (java.nio.channels.SocketChannel) field.get(connection);
                return channel == null ? "closed" : channel.getLocalAddress() + " -> " + channel.getRemoteAddress();
            } catch (Exception unavailable) {
                return unavailable.toString();
            }
        }

        void read(Socket socket) {
            RespCodec.Decoder decoder = new RespCodec.Decoder();
            byte[] buffer = new byte[4096];
            try {
                int length;
                while ((length = socket.getInputStream().read(buffer)) >= 0) {
                    bytesRead.addAndGet(length);
                    decoder.feed(buffer, length);
                    RespValue value;
                    while ((value = decoder.poll()) != null) {
                        requests.add(new Request(socket, new ArrayList<RespValue>(((RespValue.Array) value).values)));
                    }
                }
            } catch (IOException ignored) {
                // Tests deliberately close connected sockets to inject ambiguous failures.
            } catch (RuntimeException error) {
                readFailure.compareAndSet(null, error);
                throw error;
            }
        }

        @Override
        public void close() throws Exception {
            closed = true;
            listener.close();
            for (Socket socket : sockets) {
                socket.close();
            }
            acceptor.join(2000);
            for (Thread worker : workers) {
                worker.join(2000);
                assertFalse(worker.isAlive());
            }
            assertFalse(acceptor.isAlive());
        }
    }
}
