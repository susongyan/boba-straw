package io.github.susongyan.bobastraw;

import io.github.susongyan.bobastraw.protocol.RespValue;

import org.junit.jupiter.api.Test;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BobaStrawConnectionLifecycleTest {
    @Test
    void connectionCapacityRejectsBeforeWriteAndReturnsAfterResponseDrain() throws Exception {
        HoldingPingServer server = new HoldingPingServer(2);
        server.start();

        BobaStrawConnectionLimits limits = BobaStrawConnectionLimits.builder()
            .maxInFlightCommands(1)
            .maxQueuedWriteBytes(1024)
            .build();
        try (BobaStrawClient client = client(server.port(), limits)) {
            CompletableFuture<String> first = client.async().ping().toCompletableFuture();
            assertTrue(server.awaitCommand(0));
            assertEquals(1, client.metrics().inFlightCommands());
            assertEquals(0L, client.metrics().queuedWriteBytes());

            CompletableFuture<String> rejected = client.async().ping().toCompletableFuture();
            assertBackpressure(rejected);
            assertEquals(1L, client.metrics().connectionBackpressureRejections());
            assertFalse(server.awaitCommand(1, 100));

            server.allowReply(0);
            assertEquals("PONG", first.get(2, TimeUnit.SECONDS));
            assertTrue(await(() -> client.metrics().inFlightCommands() == 0));

            CompletableFuture<String> afterDrain = client.async().ping().toCompletableFuture();
            assertTrue(server.awaitCommand(1));
            server.allowReply(1);
            assertEquals("PONG", afterDrain.get(2, TimeUnit.SECONDS));
        }

        assertTrue(server.awaitCompletion());
        server.close();
    }

    @Test
    void transportCancellationKeepsItsResponseSlotUntilTheResponseIsDrained() throws Exception {
        HoldingPingServer server = new HoldingPingServer(2);
        server.start();

        BobaStrawConnectionLimits limits = BobaStrawConnectionLimits.builder()
            .maxInFlightCommands(1)
            .maxQueuedWriteBytes(1024)
            .build();
        try (BobaStrawClient client = client(server.port(), limits)) {
            CompletableFuture<RespValue> first = client.executeTransport("PING").toCompletableFuture();
            assertTrue(server.awaitCommand(0));
            assertTrue(first.cancel(false));

            assertBackpressure(client.async().ping().toCompletableFuture());
            assertEquals(1, client.metrics().inFlightCommands());

            server.allowReply(0);
            assertTrue(await(() -> client.metrics().inFlightCommands() == 0));

            CompletableFuture<String> afterDrain = client.async().ping().toCompletableFuture();
            assertTrue(server.awaitCommand(1));
            server.allowReply(1);
            assertEquals("PONG", afterDrain.get(2, TimeUnit.SECONDS));
        }

        assertTrue(server.awaitCompletion());
        server.close();
    }

    @Test
    void pipelineCapacityAdmissionIsAtomic() throws Exception {
        NoCommandServer server = new NoCommandServer();
        server.start();

        BobaStrawConnectionLimits limits = BobaStrawConnectionLimits.builder()
            .maxInFlightCommands(2)
            .maxQueuedWriteBytes(1024)
            .build();
        try (BobaStrawClient client = client(server.port(), limits)) {
            CompletableFuture<List<RespValue>> pipeline = client.pipeline()
                .command("PING")
                .command("PING")
                .command("PING")
                .execute()
                .toCompletableFuture();

            assertBackpressure(pipeline);
            assertFalse(server.awaitCommand(100));
        }

        assertTrue(server.awaitCompletion());
        server.close();
    }

    @Test
    void oversizedEncodedCommandIsRejectedBeforeSocketWrite() throws Exception {
        NoCommandServer server = new NoCommandServer();
        server.start();

        BobaStrawConnectionLimits limits = BobaStrawConnectionLimits.builder()
            .maxInFlightCommands(8)
            .maxQueuedWriteBytes(1)
            .build();
        try (BobaStrawClient client = client(server.port(), limits)) {
            assertBackpressure(client.async().set("key", "value").toCompletableFuture());
            assertEquals(1L, client.metrics().connectionBackpressureRejections());
            assertFalse(server.awaitCommand(100));
        }

        assertTrue(server.awaitCompletion());
        server.close();
    }

    @Test
    void failedHandshakeUsesCappedExponentialReconnectAndReportsReadyMetrics() throws Exception {
        HelloFlakyServer server = new HelloFlakyServer(3);
        server.start();

        try (BobaStrawClient client = BobaStrawClient.builder()
            .endpoint("127.0.0.1", server.port())
            .protocol(ProtocolVersion.AUTO)
            .commandTimeout(Duration.ofSeconds(1))
            .reconnectInterval(Duration.ofMillis(40))
            .reconnectMaxInterval(Duration.ofMillis(100))
            .build()) {
            assertTrue(server.awaitReadyConnection());
            assertTrue(await(() -> client.metrics().sharedConnectionState()
                == BobaStrawConnectionState.READY));

            BobaStrawClientMetrics metrics = client.metrics();
            assertEquals(4L, metrics.connectionCreations());
            assertEquals(3L, metrics.reconnectAttempts());
            assertEquals(1L, metrics.successfulReconnects());
            assertEquals(0, metrics.consecutiveReconnectFailures());
            assertEquals(Duration.ZERO, metrics.nextReconnectDelay());

            long[] accepts = server.acceptedAtNanos();
            assertTrue(accepts[1] - accepts[0] >= TimeUnit.MILLISECONDS.toNanos(15));
            assertTrue(accepts[2] - accepts[1] >= TimeUnit.MILLISECONDS.toNanos(45));
            assertTrue(accepts[3] - accepts[2] >= TimeUnit.MILLISECONDS.toNanos(65));
        }

        assertTrue(server.awaitCompletion());
        server.close();
    }

    private static BobaStrawClient client(int port, BobaStrawConnectionLimits limits) {
        return BobaStrawClient.builder()
            .endpoint("127.0.0.1", port)
            .protocol(ProtocolVersion.RESP2)
            .commandTimeout(Duration.ofSeconds(2))
            .connectionLimits(limits)
            .build();
    }

    private static void assertBackpressure(CompletableFuture<?> result) throws Exception {
        try {
            result.get(2, TimeUnit.SECONDS);
            throw new AssertionError("Expected connection capacity rejection");
        } catch (ExecutionException error) {
            assertTrue(error.getCause() instanceof BobaStrawBackpressureException);
        }
    }

    private static boolean await(Condition condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            if (condition.matches()) {
                return true;
            }
            Thread.sleep(5L);
        }
        return condition.matches();
    }

    private interface Condition {
        boolean matches();
    }

    private static final class HoldingPingServer implements AutoCloseable {
        private final ServerSocket serverSocket = new ServerSocket(0);
        private final CountDownLatch[] commands;
        private final CountDownLatch[] replies;
        private final CountDownLatch complete = new CountDownLatch(1);
        private final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();

        private HoldingPingServer(int count) throws IOException {
            this.commands = new CountDownLatch[count];
            this.replies = new CountDownLatch[count];
            for (int index = 0; index < count; index++) {
                commands[index] = new CountDownLatch(1);
                replies[index] = new CountDownLatch(1);
            }
        }

        private int port() {
            return serverSocket.getLocalPort();
        }

        private void start() {
            Thread thread = new Thread(new Runnable() {
                @Override
                public void run() {
                    try (Socket socket = serverSocket.accept()) {
                        BufferedInputStream input = new BufferedInputStream(socket.getInputStream());
                        BufferedOutputStream output = new BufferedOutputStream(socket.getOutputStream());
                        for (int index = 0; index < commands.length; index++) {
                            assertEquals(Arrays.asList("PING"), readCommand(input));
                            commands[index].countDown();
                            awaitLatch(replies[index]);
                            write(output, "+PONG\r\n");
                        }
                        awaitClientClose(input);
                    } catch (Throwable error) {
                        failure.set(error);
                    } finally {
                        complete.countDown();
                    }
                }
            }, "boba-straw-holding-ping-server");
            thread.setDaemon(true);
            thread.start();
        }

        private boolean awaitCommand(int index) throws InterruptedException {
            return commands[index].await(2, TimeUnit.SECONDS);
        }

        private boolean awaitCommand(int index, long timeoutMillis) throws InterruptedException {
            return commands[index].await(timeoutMillis, TimeUnit.MILLISECONDS);
        }

        private void allowReply(int index) {
            replies[index].countDown();
        }

        private boolean awaitCompletion() throws InterruptedException {
            boolean completed = complete.await(2, TimeUnit.SECONDS);
            if (failure.get() != null) {
                throw new AssertionError("Holding ping server failed", failure.get());
            }
            return completed;
        }

        @Override
        public void close() throws IOException {
            serverSocket.close();
        }
    }

    private static final class NoCommandServer implements AutoCloseable {
        private final ServerSocket serverSocket = new ServerSocket(0);
        private final CountDownLatch command = new CountDownLatch(1);
        private final CountDownLatch complete = new CountDownLatch(1);
        private final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();

        private NoCommandServer() throws IOException {
        }

        private int port() {
            return serverSocket.getLocalPort();
        }

        private void start() {
            Thread thread = new Thread(new Runnable() {
                @Override
                public void run() {
                    try (Socket socket = serverSocket.accept()) {
                        int first = socket.getInputStream().read();
                        if (first != -1) {
                            command.countDown();
                        }
                    } catch (Throwable error) {
                        failure.set(error);
                    } finally {
                        complete.countDown();
                    }
                }
            }, "boba-straw-no-command-server");
            thread.setDaemon(true);
            thread.start();
        }

        private boolean awaitCommand(long timeoutMillis) throws InterruptedException {
            return command.await(timeoutMillis, TimeUnit.MILLISECONDS);
        }

        private boolean awaitCompletion() throws InterruptedException {
            boolean completed = complete.await(2, TimeUnit.SECONDS);
            if (failure.get() != null) {
                throw new AssertionError("No-command server failed", failure.get());
            }
            return completed;
        }

        @Override
        public void close() throws IOException {
            serverSocket.close();
        }
    }

    private static final class HelloFlakyServer implements AutoCloseable {
        private final ServerSocket serverSocket = new ServerSocket(0);
        private final int failuresBeforeReady;
        private final long[] acceptedAtNanos;
        private final CountDownLatch readyConnection = new CountDownLatch(1);
        private final CountDownLatch complete = new CountDownLatch(1);
        private final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();

        private HelloFlakyServer(int failuresBeforeReady) throws IOException {
            this.failuresBeforeReady = failuresBeforeReady;
            this.acceptedAtNanos = new long[failuresBeforeReady + 1];
        }

        private int port() {
            return serverSocket.getLocalPort();
        }

        private void start() {
            Thread thread = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        for (int index = 0; index <= failuresBeforeReady; index++) {
                            try (Socket socket = serverSocket.accept()) {
                                acceptedAtNanos[index] = System.nanoTime();
                                BufferedInputStream input = new BufferedInputStream(socket.getInputStream());
                                assertEquals(Arrays.asList("HELLO", "3"), readCommand(input));
                                if (index < failuresBeforeReady) {
                                    continue;
                                }
                                BufferedOutputStream output = new BufferedOutputStream(socket.getOutputStream());
                                write(output, "+OK\r\n");
                                readyConnection.countDown();
                                awaitClientClose(input);
                            }
                        }
                    } catch (Throwable error) {
                        failure.set(error);
                    } finally {
                        complete.countDown();
                    }
                }
            }, "boba-straw-hello-flaky-server");
            thread.setDaemon(true);
            thread.start();
        }

        private boolean awaitReadyConnection() throws InterruptedException {
            return readyConnection.await(3, TimeUnit.SECONDS);
        }

        private long[] acceptedAtNanos() {
            return acceptedAtNanos.clone();
        }

        private boolean awaitCompletion() throws InterruptedException {
            boolean completed = complete.await(2, TimeUnit.SECONDS);
            if (failure.get() != null) {
                throw new AssertionError("HELLO flaky server failed", failure.get());
            }
            return completed;
        }

        @Override
        public void close() throws IOException {
            serverSocket.close();
        }
    }

    private static List<String> readCommand(BufferedInputStream input) throws IOException {
        int marker = input.read();
        if (marker != '*') {
            throw new IOException("Expected RESP array command");
        }
        int count = Integer.parseInt(readLine(input));
        List<String> values = new ArrayList<String>(count);
        for (int index = 0; index < count; index++) {
            if (input.read() != '$') {
                throw new IOException("Expected RESP bulk command argument");
            }
            int length = Integer.parseInt(readLine(input));
            byte[] value = new byte[length];
            readFully(input, value);
            if (input.read() != '\r' || input.read() != '\n') {
                throw new IOException("Expected RESP bulk terminator");
            }
            values.add(new String(value, StandardCharsets.UTF_8));
        }
        return values;
    }

    private static String readLine(BufferedInputStream input) throws IOException {
        StringBuilder value = new StringBuilder();
        int current;
        while ((current = input.read()) != -1) {
            if (current == '\r') {
                if (input.read() != '\n') {
                    throw new IOException("Malformed RESP line");
                }
                return value.toString();
            }
            value.append((char) current);
        }
        throw new IOException("Unexpected end of RESP command");
    }

    private static void readFully(BufferedInputStream input, byte[] target) throws IOException {
        int offset = 0;
        while (offset < target.length) {
            int read = input.read(target, offset, target.length - offset);
            if (read == -1) {
                throw new IOException("Unexpected end of RESP bulk command");
            }
            offset += read;
        }
    }

    private static void write(BufferedOutputStream output, String value) throws IOException {
        output.write(value.getBytes(StandardCharsets.UTF_8));
        output.flush();
    }

    private static void awaitLatch(CountDownLatch latch) throws IOException {
        try {
            if (!latch.await(2, TimeUnit.SECONDS)) {
                throw new IOException("Timed out waiting for test coordination");
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for test coordination", error);
        }
    }

    private static void awaitClientClose(BufferedInputStream input) throws IOException {
        while (input.read() != -1) {
            // The client should not write more commands while this server waits for close.
        }
    }
}
