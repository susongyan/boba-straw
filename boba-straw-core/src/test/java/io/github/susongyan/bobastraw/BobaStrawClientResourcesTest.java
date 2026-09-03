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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BobaStrawClientResourcesTest {
    @Test
    void externalResourcesShareOneEventLoopAndRemainOpenForOtherClients() throws Exception {
        PingServer first = new PingServer(1, true);
        PingServer second = new PingServer(2, true);
        first.start();
        second.start();

        BobaStrawClientResources resources = BobaStrawClientResources.builder()
            .eventLoopThreads(1)
            .build();
        BobaStrawClient clientOne = client(resources, first.port());
        BobaStrawClient clientTwo = client(resources, second.port());
        AtomicReference<String> firstThread = new AtomicReference<String>();
        AtomicReference<String> secondThread = new AtomicReference<String>();
        try {
            CompletableFuture<String> firstReply = clientOne.async().ping()
                .thenApply(value -> {
                    firstThread.set(Thread.currentThread().getName());
                    return value;
                }).toCompletableFuture();
            CompletableFuture<String> secondReply = clientTwo.async().ping()
                .thenApply(value -> {
                    secondThread.set(Thread.currentThread().getName());
                    return value;
                }).toCompletableFuture();

            assertTrue(first.awaitFirstCommand());
            assertTrue(second.awaitFirstCommand());
            first.allowFirstReply();
            second.allowFirstReply();
            assertEquals("PONG", firstReply.get(2, TimeUnit.SECONDS));
            assertEquals("PONG", secondReply.get(2, TimeUnit.SECONDS));
            assertEquals(firstThread.get(), secondThread.get());
            assertTrue(firstThread.get().startsWith("boba-straw-callback-"));

            clientOne.close();
            assertTrue(resources.isOpen());
            assertEquals("PONG", clientTwo.sync().ping());
        } finally {
            clientOne.close();
            clientTwo.close();
            resources.close();
        }

        assertTrue(first.awaitCompletion());
        assertTrue(second.awaitCompletion());
        first.close();
        second.close();
    }

    @Test
    void blockingApplicationCompletionDoesNotBlockTheSharedEventLoop() throws Exception {
        PingServer first = new PingServer(1, false);
        PingServer second = new PingServer(1, false);
        first.start();
        second.start();

        CountDownLatch callbackStarted = new CountDownLatch(1);
        CountDownLatch allowCallback = new CountDownLatch(1);
        BobaStrawClientResources resources = BobaStrawClientResources.builder()
            .eventLoopThreads(1)
            .callbackThreads(1)
            .callbackQueueCapacity(8)
            .build();
        BobaStrawClient firstClient = client(resources, first.port());
        BobaStrawClient secondClient = client(resources, second.port());
        try {
            CompletableFuture<String> firstReply = firstClient.async().ping()
                .thenApply(value -> {
                    callbackStarted.countDown();
                    try {
                        if (!allowCallback.await(2, TimeUnit.SECONDS)) {
                            throw new AssertionError("Timed out waiting to release the callback");
                        }
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError("Interrupted while waiting to release the callback", error);
                    }
                    return value;
                }).toCompletableFuture();

            assertTrue(first.awaitFirstCommand());
            assertTrue(callbackStarted.await(2, TimeUnit.SECONDS));

            CompletableFuture<String> secondReply = secondClient.async().ping().toCompletableFuture();
            assertTrue(
                second.awaitFirstCommand(),
                "The second command must reach Redis while the first application callback is blocked"
            );

            allowCallback.countDown();
            assertEquals("PONG", firstReply.get(2, TimeUnit.SECONDS));
            assertEquals("PONG", secondReply.get(2, TimeUnit.SECONDS));
        } finally {
            allowCallback.countDown();
            firstClient.close();
            secondClient.close();
            resources.close();
        }

        assertTrue(first.awaitCompletion());
        assertTrue(second.awaitCompletion());
        first.close();
        second.close();
    }

    @Test
    void callbackCapacityRejectsACommandBeforeItIsWritten() throws Exception {
        PingServer server = new PingServer(2, true);
        server.start();

        BobaStrawClientResources resources = BobaStrawClientResources.builder()
            .callbackThreads(1)
            .callbackQueueCapacity(1)
            .build();
        BobaStrawClient client = client(resources, server.port());
        try {
            CompletableFuture<RespValue> first =
                client.executeAsync("PING").toCompletableFuture();
            assertTrue(server.awaitFirstCommand());
            CompletableFuture<RespValue> second =
                client.executeAsync("PING").toCompletableFuture();
            CompletableFuture<RespValue> rejected =
                client.executeAsync("PING").toCompletableFuture();

            try {
                rejected.get(2, TimeUnit.SECONDS);
                throw new AssertionError("Expected callback capacity to reject the third command");
            } catch (ExecutionException error) {
                assertTrue(error.getCause() instanceof BobaStrawBackpressureException);
            }

            server.allowFirstReply();
            assertEquals("PONG", first.get(2, TimeUnit.SECONDS).asString());
            assertEquals("PONG", second.get(2, TimeUnit.SECONDS).asString());
        } finally {
            client.close();
            resources.close();
        }

        assertTrue(server.awaitCompletion());
        server.close();
    }

    @Test
    void oneConnectionFailureDoesNotStopOtherConnectionsOnTheSameLoop() throws Exception {
        DisconnectingServer failing = new DisconnectingServer();
        PingServer healthy = new PingServer(1, false);
        failing.start();
        healthy.start();

        BobaStrawClientResources resources = BobaStrawClientResources.builder().build();
        BobaStrawClient failedClient = client(resources, failing.port());
        BobaStrawClient healthyClient = client(resources, healthy.port());
        try {
            CompletableFuture<String> failed = failedClient.async().ping().toCompletableFuture();
            assertTrue(failing.awaitCommand());
            try {
                failed.get(2, TimeUnit.SECONDS);
                throw new AssertionError("Expected the disconnected command to fail");
            } catch (ExecutionException error) {
                assertTrue(error.getCause() instanceof BobaStrawCommandMayHaveExecutedException);
            }

            assertEquals("PONG", healthyClient.sync().ping());
        } finally {
            failedClient.close();
            healthyClient.close();
            resources.close();
        }

        assertTrue(failing.awaitCompletion());
        assertTrue(healthy.awaitCompletion());
        failing.close();
        healthy.close();
    }

    @Test
    void resourceShutdownFailsInFlightWorkAndRejectsNewCommands() throws Exception {
        SlowServer server = new SlowServer();
        server.start();

        BobaStrawClientResources resources = BobaStrawClientResources.builder().build();
        BobaStrawClient client = client(resources, server.port());
        try {
            CompletableFuture<String> pending = client.async().ping().toCompletableFuture();
            assertTrue(server.awaitCommand());
            resources.close();
            assertFalse(resources.isOpen());
            try {
                pending.get(2, TimeUnit.SECONDS);
                throw new AssertionError("Expected EventLoop shutdown to fail the in-flight command");
            } catch (ExecutionException error) {
                assertTrue(error.getCause() instanceof BobaStrawConnectionException);
            }
            assertThrows(BobaStrawConnectionException.class, () -> client.async().ping());
        } finally {
            client.close();
        }

        assertTrue(server.awaitCompletion());
        server.close();
    }

    @Test
    void failedOwnedClusterBootstrapReleasesItsEventLoop() throws Exception {
        ServerSocket reservation = new ServerSocket(0);
        int unavailablePort = reservation.getLocalPort();
        reservation.close();
        int threadsBefore = eventLoopThreadCount();

        assertThrows(BobaStrawConnectionException.class, () -> BobaStrawClusterClient.builder()
            .seed("127.0.0.1", unavailablePort)
            .protocol(ProtocolVersion.RESP2)
            .commandTimeout(Duration.ofMillis(200))
            .build());

        assertEquals(threadsBefore, eventLoopThreadCount());
    }

    private static BobaStrawClient client(BobaStrawClientResources resources, int port) {
        return BobaStrawClient.builder()
            .resources(resources)
            .endpoint("127.0.0.1", port)
            .protocol(ProtocolVersion.RESP2)
            .commandTimeout(Duration.ofSeconds(2))
            .build();
    }

    private static final class PingServer implements AutoCloseable {
        private final ServerSocket serverSocket = new ServerSocket(0);
        private final int expectedCommands;
        private final boolean holdFirstReply;
        private final CountDownLatch firstCommand = new CountDownLatch(1);
        private final CountDownLatch firstReplyAllowed = new CountDownLatch(1);
        private final CountDownLatch complete = new CountDownLatch(1);
        private final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();

        private PingServer(int expectedCommands, boolean holdFirstReply) throws IOException {
            this.expectedCommands = expectedCommands;
            this.holdFirstReply = holdFirstReply;
            if (!holdFirstReply) {
                firstReplyAllowed.countDown();
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
                        for (int index = 0; index < expectedCommands; index++) {
                            assertEquals(Arrays.asList("PING"), readCommand(input));
                            if (index == 0) {
                                firstCommand.countDown();
                                await(firstReplyAllowed);
                            }
                            write(output, "+PONG\r\n");
                        }
                        awaitClientClose(input);
                    } catch (Throwable error) {
                        failure.set(error);
                    } finally {
                        complete.countDown();
                    }
                }
            }, "fake-ping-server");
            thread.setDaemon(true);
            thread.start();
        }

        private boolean awaitFirstCommand() throws InterruptedException {
            return firstCommand.await(2, TimeUnit.SECONDS);
        }

        private void allowFirstReply() {
            firstReplyAllowed.countDown();
        }

        private boolean awaitCompletion() throws InterruptedException {
            boolean finished = complete.await(2, TimeUnit.SECONDS);
            if (failure.get() != null) {
                throw new AssertionError("Fake ping server failed", failure.get());
            }
            return finished;
        }

        @Override
        public void close() throws IOException {
            serverSocket.close();
        }
    }

    private static final class DisconnectingServer implements AutoCloseable {
        private final ServerSocket serverSocket = new ServerSocket(0);
        private final CountDownLatch command = new CountDownLatch(1);
        private final CountDownLatch complete = new CountDownLatch(1);
        private final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();

        private DisconnectingServer() throws IOException {
        }

        private int port() {
            return serverSocket.getLocalPort();
        }

        private void start() {
            Thread thread = new Thread(new Runnable() {
                @Override
                public void run() {
                    try (Socket socket = serverSocket.accept()) {
                        assertEquals(Arrays.asList("PING"), readCommand(
                            new BufferedInputStream(socket.getInputStream())
                        ));
                        command.countDown();
                    } catch (Throwable error) {
                        failure.set(error);
                    } finally {
                        complete.countDown();
                    }
                }
            }, "fake-disconnecting-server");
            thread.setDaemon(true);
            thread.start();
        }

        private boolean awaitCommand() throws InterruptedException {
            return command.await(2, TimeUnit.SECONDS);
        }

        private boolean awaitCompletion() throws InterruptedException {
            boolean finished = complete.await(2, TimeUnit.SECONDS);
            if (failure.get() != null) {
                throw new AssertionError("Fake disconnecting server failed", failure.get());
            }
            return finished;
        }

        @Override
        public void close() throws IOException {
            serverSocket.close();
        }
    }

    private static final class SlowServer implements AutoCloseable {
        private final ServerSocket serverSocket = new ServerSocket(0);
        private final CountDownLatch command = new CountDownLatch(1);
        private final CountDownLatch complete = new CountDownLatch(1);
        private final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();

        private SlowServer() throws IOException {
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
                        assertEquals(Arrays.asList("PING"), readCommand(input));
                        command.countDown();
                        awaitClientClose(input);
                    } catch (Throwable error) {
                        failure.set(error);
                    } finally {
                        complete.countDown();
                    }
                }
            }, "fake-slow-server");
            thread.setDaemon(true);
            thread.start();
        }

        private boolean awaitCommand() throws InterruptedException {
            return command.await(2, TimeUnit.SECONDS);
        }

        private boolean awaitCompletion() throws InterruptedException {
            boolean finished = complete.await(2, TimeUnit.SECONDS);
            if (failure.get() != null) {
                throw new AssertionError("Fake slow server failed", failure.get());
            }
            return finished;
        }

        @Override
        public void close() throws IOException {
            serverSocket.close();
        }
    }

    private static void await(CountDownLatch latch) throws IOException {
        try {
            if (!latch.await(2, TimeUnit.SECONDS)) {
                throw new IOException("Timed out waiting for test coordination");
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for test coordination", error);
        }
    }

    private static List<String> readCommand(BufferedInputStream input) throws IOException {
        if (input.read() != '*') {
            throw new IOException("Expected RESP array command");
        }
        int count = Integer.parseInt(readLine(input));
        List<String> command = new ArrayList<String>(count);
        for (int index = 0; index < count; index++) {
            if (input.read() != '$') {
                throw new IOException("Expected RESP bulk string command argument");
            }
            int bytes = Integer.parseInt(readLine(input));
            byte[] value = new byte[bytes];
            int offset = 0;
            while (offset < bytes) {
                int read = input.read(value, offset, bytes - offset);
                if (read < 0) {
                    throw new IOException("Unexpected end of command");
                }
                offset += read;
            }
            expectCrLf(input);
            command.add(new String(value, StandardCharsets.UTF_8));
        }
        return command;
    }

    private static String readLine(BufferedInputStream input) throws IOException {
        StringBuilder line = new StringBuilder();
        int value;
        while ((value = input.read()) >= 0) {
            if (value == '\r') {
                if (input.read() != '\n') {
                    throw new IOException("Expected LF after CR");
                }
                return line.toString();
            }
            line.append((char) value);
        }
        throw new IOException("Unexpected end of input");
    }

    private static void expectCrLf(BufferedInputStream input) throws IOException {
        if (input.read() != '\r' || input.read() != '\n') {
            throw new IOException("Expected CRLF");
        }
    }

    private static void write(BufferedOutputStream output, String response) throws IOException {
        output.write(response.getBytes(StandardCharsets.UTF_8));
        output.flush();
    }

    private static void awaitClientClose(BufferedInputStream input) throws IOException {
        while (input.read() >= 0) {
            // The client or its shared resources own the physical connection lifecycle.
        }
    }

    private static int eventLoopThreadCount() {
        int result = 0;
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            if (thread.isAlive() && thread.getName().startsWith("boba-straw-nio-")) {
                result++;
            }
        }
        return result;
    }
}
