package io.github.susongyan.bobastraw;

import io.github.susongyan.bobastraw.protocol.RespCodec;
import io.github.susongyan.bobastraw.protocol.RespValue;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class BinaryStringCommandsTest {
    private static final byte[] KEY = {0, (byte) 0xff};
    private static final byte[] VALUE = {(byte) 0xfe, 0};

    @Test
    void encodedLargeValueSurvivesCallerMutationAndMultipleWriteBudgets() throws Exception {
        byte[] value = new byte[131072];
        java.util.Arrays.fill(value, (byte) 0xfe);
        byte[] expected = value.clone();
        byte[] key = KEY.clone();
        CountDownLatch read = new CountDownLatch(1);
        try (Server server = new Server(socket -> {
            assertTrue(read.await(3, TimeUnit.SECONDS));
            exchange(socket, "+OK\r\n", ascii("SET"), KEY, expected);
            exchange(socket, ":131072\r\n", ascii("STRLEN"), KEY);
        }); BobaStrawClient client = client(server)) {
            try {
                CompletionStage<byte[]> set = client.binary().set(key, value);
                java.util.Arrays.fill(value, (byte) 'H');
                java.util.Arrays.fill(key, (byte) 'H');
                CompletionStage<Long> next = client.binary().strlen(KEY);
                read.countDown();
                assertArrayEquals(ascii("OK"), await(set));
                assertEquals(Long.valueOf(131072), await(next));
                server.verify();
            } finally {
                read.countDown();
            }
        }
    }

    @Test
    void encodedFrameStillHonorsQueuedByteAdmissionBeforeWriting() throws Exception {
        try (Server server = new Server(socket -> exchange(socket, "$-1\r\n", ascii("GET"), KEY));
             BobaStrawClient client = BobaStrawClient.builder()
                 .endpoint("127.0.0.1", server.listener.getLocalPort()).protocol(ProtocolVersion.RESP2)
                 .commandTimeout(Duration.ofSeconds(3))
                 .connectionLimits(BobaStrawConnectionLimits.builder().maxQueuedWriteBytes(64).build())
                 .build()) {
            java.util.concurrent.ExecutionException failure = assertThrows(
                java.util.concurrent.ExecutionException.class,
                () -> await(client.binary().set(KEY, new byte[128])));
            assertTrue(failure.getCause() instanceof BobaStrawBackpressureException);
            assertNull(await(client.binary().get(KEY)));
            server.verify();
        }
    }

    @Test
    void encodesOptionsPairsAndOffsetsWithoutConvertingPayloads() throws Exception {
        try (Server server = new Server(socket -> {
            exchange(socket, "+OK\r\n", ascii("SET"), KEY, VALUE, ascii("NX"), ascii("PX"), ascii("30"));
            exchange(socket, "+OK\r\n", ascii("MSET"), KEY, VALUE, new byte[0], new byte[0]);
            exchange(socket, ":0\r\n", ascii("MSETNX"), KEY, VALUE);
            exchange(socket, ":1\r\n", ascii("MSETNX"), KEY, VALUE);
            exchange(socket, ":4\r\n", ascii("APPEND"), KEY, VALUE);
            exchange(socket, ":4\r\n", ascii("STRLEN"), KEY);
            exchange(socket, "$0\r\n\r\n", ascii("GETRANGE"), KEY, ascii("-2"), ascii("-1"));
            exchange(socket, ":5\r\n", ascii("SETRANGE"), KEY, ascii("3"), VALUE);
        }); BobaStrawClient client = client(server)) {
            BobaStrawBinaryCommands binary = client.binary();
            assertArrayEquals(ascii("OK"), await(binary.set(KEY, VALUE, SetArgs.nx().px(30))));
            assertArrayEquals(ascii("OK"), await(binary.mset(KEY, VALUE, new byte[0], new byte[0])));
            assertFalse(await(binary.msetNx(KEY, VALUE)));
            assertTrue(await(binary.msetNx(KEY, VALUE)));
            assertEquals(Long.valueOf(4), await(binary.append(KEY, VALUE)));
            assertEquals(Long.valueOf(4), await(binary.strlen(KEY)));
            assertArrayEquals(new byte[0], await(binary.getRange(KEY, -2, -1)));
            assertEquals(Long.valueOf(5), await(binary.setRange(KEY, 3, VALUE)));
            server.verify();
        }
    }

    @Test
    void preservesMissingEmptyDuplicateAndNonUtf8ResultsInBothProtocols() throws Exception {
        // Historical name retained for report continuity. This tests both null encodings,
        // not negotiation: the legacy fixture deliberately stays on RESP2 in both cases.
        int repetitions = Integer.getInteger("boba.straw.binaryDiagnosticRepetitions", 1);
        if (repetitions < 1 || repetitions > 1000) {
            throw new IllegalArgumentException("Binary diagnostic repetitions must be between 1 and 1000");
        }
        for (int iteration = 0; iteration < repetitions; iteration++) {
            verifyBinaryReplies();
        }
    }

    private void verifyBinaryReplies() throws Exception {
        for (String nil : new String[] {"$-1\r\n", "_\r\n"}) {
            java.util.concurrent.atomic.AtomicReference<BinaryFailureEvidence> evidence =
                new java.util.concurrent.atomic.AtomicReference<BinaryFailureEvidence>();
            try (Server server = new Server(socket -> {
                readCommand(socket, ascii("MGET"), KEY, KEY, new byte[0], ascii("missing"));
                writeBinaryReply(socket, evidence.get(), nil);
                socket.getOutputStream().flush();
            }); BobaStrawClient client = client(server)) {
                evidence.set(new BinaryFailureEvidence(client));
                List<byte[]> values;
                try {
                    values = await(client.binary().mget(KEY, KEY, new byte[0], ascii("missing")));
                } catch (Exception error) {
                    // Test-only endpoint/lifecycle evidence, without logging application payloads.
                    error.addSuppressed(new IllegalStateException(server.diagnosticState()
                        + ", " + evidence.get().snapshot()));
                    throw error;
                }
                assertEquals(4, values.size());
                assertArrayEquals(VALUE, values.get(0));
                assertArrayEquals(VALUE, values.get(1));
                assertArrayEquals(new byte[0], values.get(2));
                assertNull(values.get(3));
                server.verify();
            }
        }
    }

    private static void writeBinaryReply(Socket socket, BinaryFailureEvidence evidence, String nil) throws Exception {
        evidence.write(socket.getOutputStream(), ascii("*4\r\n$2\r\n"));
        evidence.write(socket.getOutputStream(), VALUE);
        evidence.write(socket.getOutputStream(), ascii("\r\n$2\r\n"));
        evidence.write(socket.getOutputStream(), VALUE);
        evidence.write(socket.getOutputStream(), ascii("\r\n$0\r\n\r\n" + nil));
    }

    @Test
    void negotiatedProtocolUsesMatchingBinaryNullReply() throws Exception {
        for (ProtocolVersion protocol : new ProtocolVersion[] {ProtocolVersion.RESP2, ProtocolVersion.AUTO}) {
            java.util.concurrent.atomic.AtomicReference<BinaryFailureEvidence> evidence =
                new java.util.concurrent.atomic.AtomicReference<BinaryFailureEvidence>();
            try (Server server = new Server(socket -> {
                if (protocol == ProtocolVersion.AUTO) {
                    exchange(socket, "%1\r\n+proto\r\n:3\r\n", ascii("HELLO"), ascii("3"));
                }
                readCommand(socket, ascii("MGET"), KEY, KEY, new byte[0], ascii("missing"));
                writeBinaryReply(socket, evidence.get(), protocol == ProtocolVersion.RESP2 ? "$-1\r\n" : "_\r\n");
                socket.getOutputStream().flush();
            }); BobaStrawClient client = BobaStrawClient.builder()
                    .endpoint("127.0.0.1", server.listener.getLocalPort()).protocol(protocol)
                    .commandTimeout(Duration.ofSeconds(3)).build()) {
                evidence.set(new BinaryFailureEvidence(client));
                try {
                    List<byte[]> values = await(client.binary().mget(KEY, KEY, new byte[0], ascii("missing")));
                    assertEquals(4, values.size());
                    assertArrayEquals(VALUE, values.get(0));
                    assertArrayEquals(VALUE, values.get(1));
                    assertArrayEquals(new byte[0], values.get(2));
                    assertNull(values.get(3));
                    server.verify();
                } catch (Exception error) {
                    error.addSuppressed(new IllegalStateException(server.diagnosticState()
                        + ", " + evidence.get().snapshot()));
                    throw error;
                }
            }
        }
    }

    @Test
    void syntheticInvalidHIsVisibleInServerSocketAndDecoderEvidence() throws Exception {
        java.util.concurrent.atomic.AtomicReference<BinaryFailureEvidence> evidence =
            new java.util.concurrent.atomic.AtomicReference<BinaryFailureEvidence>();
        try (Server server = new Server(socket -> {
            readCommand(socket, ascii("MGET"), KEY);
            evidence.get().write(socket.getOutputStream(), ascii("H\r\n"));
            socket.getOutputStream().flush();
        }); BobaStrawClient client = client(server)) {
            evidence.set(new BinaryFailureEvidence(client));
            java.util.concurrent.ExecutionException failure = assertThrows(
                java.util.concurrent.ExecutionException.class, () -> await(client.binary().mget(KEY)));
            assertTrue(failure.getCause() instanceof BobaStrawCommandMayHaveExecutedException);
            Throwable root = failure;
            while (root.getCause() != null) {
                root = root.getCause();
            }
            assertEquals("Unsupported RESP marker: H", root.getMessage());
            String snapshot = evidence.get().snapshot();
            assertTrue(snapshot.contains("serverAttempted=480d0a"), snapshot);
            assertTrue(snapshot.contains("serverWriteCompletedBytes=3"), snapshot);
            // The decoder may fail as soon as H arrives, before TCP delivers CRLF.
            assertTrue(snapshot.contains("decoderWindow=48"), snapshot);
            assertTrue(snapshot.contains("decoderRead=0"), snapshot);
            // TCP may fragment the three bytes; the last chunk is not necessarily the whole reply.
            assertTrue(snapshot.contains("lastSocketChunkBytes="), snapshot);
            assertFalse(snapshot.contains("snapshotUnavailable"), snapshot);
            server.verify();
        }
    }

    @Test
    void validatesNewArgumentsBeforeClientAccess() {
        // A null transport proves invalid input never reaches connection submission.
        BobaStrawBinaryCommands binary = new BobaStrawBinaryCommands(null);
        assertThrows(IllegalArgumentException.class, () -> binary.mget());
        assertThrows(IllegalArgumentException.class, () -> binary.mget((byte[][]) null));
        assertThrows(IllegalArgumentException.class, () -> binary.mget(KEY, null));
        assertThrows(IllegalArgumentException.class, () -> binary.mset());
        assertThrows(IllegalArgumentException.class, () -> binary.mset(KEY));
        assertThrows(IllegalArgumentException.class, () -> binary.msetNx((byte[][]) null));
        assertThrows(IllegalArgumentException.class, () -> binary.msetNx(KEY, null));
        assertThrows(IllegalArgumentException.class, () -> binary.set(KEY, VALUE, null));
        assertThrows(IllegalArgumentException.class, () -> binary.set(null, VALUE, SetArgs.none()));
        assertThrows(IllegalArgumentException.class, () -> binary.append(KEY, null));
        assertThrows(IllegalArgumentException.class, () -> binary.strlen(null));
        assertThrows(IllegalArgumentException.class, () -> binary.getRange(null, 0, -1));
        assertThrows(IllegalArgumentException.class, () -> binary.setRange(KEY, -1, VALUE));
    }

    @Test
    void existingStringClusterMetadataUsesAllBatchKeysButNotValues() {
        Integer slot = ClusterSlot.of("{batch}:1");
        assertEquals(slot, ClusterCommandRouting.slot("MGET", new String[] {"{batch}:1", "{batch}:2"}));
        for (String command : new String[] {"MSET", "MSETNX"}) {
            assertEquals(slot, ClusterCommandRouting.slot(command,
                new String[] {"{batch}:1", "unrelated-value", "{batch}:2", "another-value"}));
            assertThrows(IllegalArgumentException.class, () -> ClusterCommandRouting.slot(command,
                new String[] {"{batch}:1", "value", "{other}:2", "value"}));
            assertThrows(IllegalArgumentException.class, () -> ClusterCommandRouting.slot(command,
                new String[] {"{batch}:1"}));
        }
        assertThrows(IllegalArgumentException.class, () -> ClusterCommandRouting.slot("MGET",
            new String[] {"{batch}:1", "{other}:2"}));
        for (String command : new String[] {"SET", "APPEND", "STRLEN", "GETRANGE", "SETRANGE"}) {
            assertEquals(slot, ClusterCommandRouting.slot(command, new String[] {"{batch}:1", "0", "1"}));
        }
    }

    @Test
    void cancellingMappedMgetDrainsReplyBeforeNextCommand() throws Exception {
        CountDownLatch received = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (Server server = new Server(socket -> {
            readCommand(socket, ascii("MGET"), KEY);
            received.countDown();
            assertTrue(release.await(3, TimeUnit.SECONDS));
            exchange(socket, "*1\r\n$0\r\n\r\n:7\r\n", ascii("STRLEN"), KEY);
        }); BobaStrawClient client = client(server)) {
            CompletableFuture<List<byte[]>> cancelled = client.binary().mget(KEY).toCompletableFuture();
            try {
                assertTrue(received.await(3, TimeUnit.SECONDS));
                assertTrue(cancelled.cancel(false));
                CompletionStage<Long> next = client.binary().strlen(KEY);
                release.countDown();
                assertEquals(Long.valueOf(7), await(next));
                assertTrue(cancelled.isCancelled());
                server.verify();
            } finally {
                release.countDown();
            }
        }
    }

    private static BobaStrawClient client(Server server) {
        return BobaStrawClient.builder().endpoint("127.0.0.1", server.listener.getLocalPort())
            .protocol(ProtocolVersion.RESP2).commandTimeout(Duration.ofSeconds(3)).build();
    }

    private static <T> T await(CompletionStage<T> value) throws Exception {
        return value.toCompletableFuture().get(3, TimeUnit.SECONDS);
    }

    private static byte[] ascii(String value) {
        return value.getBytes(StandardCharsets.US_ASCII);
    }

    private static void exchange(Socket socket, String reply, byte[]... expected) throws Exception {
        readCommand(socket, expected);
        socket.getOutputStream().write(ascii(reply));
        socket.getOutputStream().flush();
    }

    private static void readCommand(Socket socket, byte[]... expected) throws Exception {
        RespCodec.Decoder decoder = new RespCodec.Decoder();
        InputStream input = socket.getInputStream();
        RespValue command;
        while ((command = decoder.poll()) == null) {
            int next = input.read();
            assertNotEquals(-1, next, "Client closed before command");
            decoder.feed(new byte[] {(byte) next}, 1);
        }
        List<RespValue> values = ((RespValue.Array) command).values;
        assertEquals(expected.length, values.size());
        for (int index = 0; index < expected.length; index++) {
            assertArrayEquals(expected[index], ((RespValue.BlobString) values.get(index)).value);
        }
    }

    private interface Scenario {
        void run(Socket socket) throws Exception;
    }

    private static final class Server implements AutoCloseable {
        private final ServerSocket listener = LoopbackTestServer.open();
        private final CompletableFuture<Void> completed = new CompletableFuture<Void>();
        private volatile Socket connection;
        private final Thread thread;

        private Server(Scenario scenario) throws Exception {
            thread = new Thread(() -> {
                try (Socket socket = listener.accept()) {
                    connection = socket;
                    socket.setSoTimeout(4000);
                    scenario.run(socket);
                    completed.complete(null);
                    // Do not race socket close with callback delivery of the final reply.
                    assertEquals(-1, socket.getInputStream().read());
                } catch (Throwable error) {
                    completed.completeExceptionally(error);
                }
            }, "binary-string-test-server");
            thread.setDaemon(true);
            thread.start();
        }

        private void verify() throws Exception {
            completed.get(4, TimeUnit.SECONDS);
        }

        private String diagnosticState() {
            Socket socket = connection;
            return "Binary fixture listener=" + listener.getLocalSocketAddress()
                + ", accepted=" + (socket != null)
                + ", peer=" + (socket == null ? "none" : socket.getRemoteSocketAddress())
                + ", scenarioCompleted=" + completed.isDone()
                + ", scenarioFailed=" + completed.isCompletedExceptionally();
        }

        @Override
        public void close() throws Exception {
            listener.close();
            Socket socket = connection;
            if (socket != null) {
                socket.close();
            }
            thread.join(5000);
            assertFalse(thread.isAlive(), "Test server must terminate");
        }
    }
}
