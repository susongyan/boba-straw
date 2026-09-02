package io.github.susongyan.bobastraw.internal;

import io.github.susongyan.bobastraw.BobaStrawConnectionException;
import io.github.susongyan.bobastraw.ProtocolVersion;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NioConnectionIoTest {
    @Test
    void gathersBoundedWritesWithoutCorruptingALargeFrameOrFifoOrder() throws Exception {
        CommandServer server = new CommandServer(2, "+OK\r\n+PONG\r\n");
        server.start();

        NioIoLimits limits = new NioIoLimits(64, 64, 2, 16, 2, 4);
        NioEventLoopGroup eventLoops = new NioEventLoopGroup(1, limits);
        NioConnection connection = connection(eventLoops.next(), server.port());
        String value = repeated('b', 64);
        try {
            CompletableFuture<RespValue> set = connection.execute(
                new String[] { "SET", "tea", value }
            ).toCompletableFuture();
            CompletableFuture<RespValue> ping = connection.execute(
                new String[] { "PING" }
            ).toCompletableFuture();

            assertTrue(server.awaitCommands());
            assertEquals(Arrays.asList("SET", "tea", value), server.commands().get(0));
            assertEquals(Arrays.asList("PING"), server.commands().get(1));
            assertEquals("OK", set.get(2, TimeUnit.SECONDS).asString());
            assertEquals("PONG", ping.get(2, TimeUnit.SECONDS).asString());
        } finally {
            connection.close();
            eventLoops.close();
        }

        assertTrue(server.awaitCompletion());
        server.close();
    }

    @Test
    void decodedResponsesAreCappedBeforeTheNextEventLoopTurn() throws Exception {
        CommandServer server = new CommandServer(5, null);
        server.start();

        NioIoLimits limits = new NioIoLimits(64, 64, 2, 32, 2, 4);
        NioEventLoopGroup eventLoops = new NioEventLoopGroup(1, limits);
        NioEventLoop eventLoop = eventLoops.next();
        NioConnection connection = connection(eventLoop, server.port());
        List<CompletableFuture<RespValue>> responses = new ArrayList<CompletableFuture<RespValue>>();
        try {
            for (int index = 0; index < 5; index++) {
                responses.add(connection.execute(new String[] { "PING" }).toCompletableFuture());
            }
            assertTrue(server.awaitCommands());

            final AtomicInteger completedInFirstSlice = new AtomicInteger(-1);
            final AtomicReference<Throwable> taskFailure = new AtomicReference<Throwable>();
            final CountDownLatch inspectionComplete = new CountDownLatch(1);
            final byte[] burst = repeatedPongs(5);
            eventLoop.execute(new NioEventLoop.Task() {
                @Override
                public void run() {
                    try {
                        connection.processInbound(burst, burst.length);
                        int completed = 0;
                        for (CompletableFuture<RespValue> response : responses) {
                            if (response.isDone()) {
                                completed++;
                            }
                        }
                        completedInFirstSlice.set(completed);
                        inspectionComplete.countDown();
                    } catch (IOException error) {
                        throw new BobaStrawConnectionException("Could not inject Redis responses", error);
                    }
                }

                @Override
                public void reject(BobaStrawConnectionException error) {
                    taskFailure.compareAndSet(null, error);
                    inspectionComplete.countDown();
                }
            });

            assertTrue(inspectionComplete.await(2, TimeUnit.SECONDS));
            assertNull(taskFailure.get());
            assertEquals(2, completedInFirstSlice.get());
            for (CompletableFuture<RespValue> response : responses) {
                assertEquals("PONG", response.get(2, TimeUnit.SECONDS).asString());
            }
        } finally {
            connection.close();
            eventLoops.close();
        }

        assertTrue(server.awaitCompletion());
        server.close();
    }

    @Test
    void responseBurstYieldsToAnotherReadyConnectionOnTheSameEventLoop() throws Exception {
        final int busyCommandCount = 8;
        CommandServer busyServer = new CommandServer(
            busyCommandCount,
            repeatedPongsText(busyCommandCount),
            true
        );
        CommandServer healthyServer = new CommandServer(1, "+PONG\r\n", true);
        busyServer.start();
        healthyServer.start();

        NioIoLimits limits = new NioIoLimits(64, 64, 2, 32, 2, 4);
        NioEventLoopGroup eventLoops = new NioEventLoopGroup(1, limits);
        NioEventLoop eventLoop = eventLoops.next();
        NioConnection busy = connection(eventLoop, busyServer.port());
        NioConnection healthy = connection(eventLoop, healthyServer.port());
        AtomicReference<Throwable> callbackFailure = new AtomicReference<Throwable>();
        AtomicBoolean busyLastCompletedWhenHealthyCompleted = new AtomicBoolean(true);
        try {
            CompletableFuture<RespValue> healthyReply = healthy.execute(
                new String[] { "PING" }
            ).toCompletableFuture();
            List<CompletableFuture<RespValue>> busyReplies =
                new ArrayList<CompletableFuture<RespValue>>();
            for (int index = 0; index < busyCommandCount; index++) {
                busyReplies.add(busy.execute(new String[] { "PING" }).toCompletableFuture());
            }
            final CompletableFuture<RespValue> busyLast = busyReplies.get(busyCommandCount - 1);
            healthyReply.whenComplete((value, error) ->
                busyLastCompletedWhenHealthyCompleted.set(busyLast.isDone())
            );

            assertTrue(healthyServer.awaitCommands());
            assertTrue(busyServer.awaitCommands());
            busyReplies.get(0).whenComplete((value, error) -> {
                if (error != null) {
                    callbackFailure.compareAndSet(null, error);
                    return;
                }
                // Test-only barrier: make the healthy socket readable before this response slice
                // continues, so the assertion observes EventLoop scheduling rather than thread timing.
                healthyServer.allowResponse();
                try {
                    if (!healthyServer.awaitResponseWritten()) {
                        callbackFailure.compareAndSet(
                            null,
                            new AssertionError("Healthy Redis response was not written")
                        );
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    callbackFailure.compareAndSet(null, interrupted);
                }
            });

            busyServer.allowResponse();
            assertEquals("PONG", healthyReply.get(2, TimeUnit.SECONDS).asString());
            assertNull(callbackFailure.get());
            assertFalse(
                busyLastCompletedWhenHealthyCompleted.get(),
                "A response burst must yield before the final busy response is dispatched"
            );
            for (CompletableFuture<RespValue> reply : busyReplies) {
                assertEquals("PONG", reply.get(2, TimeUnit.SECONDS).asString());
            }
        } finally {
            busyServer.allowResponse();
            healthyServer.allowResponse();
            busy.close();
            healthy.close();
            eventLoops.close();
        }

        assertTrue(busyServer.awaitCompletion());
        assertTrue(healthyServer.awaitCompletion());
        busyServer.close();
        healthyServer.close();
    }

    private static NioConnection connection(NioEventLoop eventLoop, int port) {
        return new NioConnection(
            eventLoop,
            "127.0.0.1",
            port,
            Duration.ofSeconds(2),
            ProtocolVersion.RESP2,
            null,
            null,
            null,
            null,
            Duration.ZERO
        );
    }

    private static byte[] repeatedPongs(int count) {
        return repeatedPongsText(count).getBytes(StandardCharsets.UTF_8);
    }

    private static String repeatedPongsText(int count) {
        StringBuilder responses = new StringBuilder();
        for (int index = 0; index < count; index++) {
            responses.append("+PONG\r\n");
        }
        return responses.toString();
    }

    private static String repeated(char character, int count) {
        StringBuilder result = new StringBuilder(count);
        for (int index = 0; index < count; index++) {
            result.append(character);
        }
        return result.toString();
    }

    private static final class CommandServer implements AutoCloseable {
        private final ServerSocket serverSocket = new ServerSocket(0);
        private final int expectedCommands;
        private final String responses;
        private final boolean holdResponse;
        private final CountDownLatch commandsReceived = new CountDownLatch(1);
        private final CountDownLatch responseAllowed = new CountDownLatch(1);
        private final CountDownLatch responseWritten = new CountDownLatch(1);
        private final CountDownLatch complete = new CountDownLatch(1);
        private final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        private volatile List<List<String>> commands = new ArrayList<List<String>>();

        private CommandServer(int expectedCommands, String responses) throws IOException {
            this(expectedCommands, responses, false);
        }

        private CommandServer(int expectedCommands, String responses, boolean holdResponse)
            throws IOException {
            this.expectedCommands = expectedCommands;
            this.responses = responses;
            this.holdResponse = holdResponse;
            if (!holdResponse || responses == null) {
                responseAllowed.countDown();
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
                        List<List<String>> received = new ArrayList<List<String>>(expectedCommands);
                        for (int index = 0; index < expectedCommands; index++) {
                            received.add(readCommand(input));
                        }
                        commands = received;
                        commandsReceived.countDown();
                        if (responses != null) {
                            if (holdResponse && !responseAllowed.await(2, TimeUnit.SECONDS)) {
                                throw new IOException("Timed out waiting to send Redis response");
                            }
                            output.write(responses.getBytes(StandardCharsets.UTF_8));
                            output.flush();
                            responseWritten.countDown();
                        }
                        awaitClientClose(input);
                    } catch (Throwable error) {
                        failure.set(error);
                    } finally {
                        complete.countDown();
                    }
                }
            }, "fake-nio-connection-server");
            thread.setDaemon(true);
            thread.start();
        }

        private boolean awaitCommands() throws InterruptedException {
            return commandsReceived.await(2, TimeUnit.SECONDS);
        }

        private List<List<String>> commands() {
            return commands;
        }

        private void allowResponse() {
            responseAllowed.countDown();
        }

        private boolean awaitResponseWritten() throws InterruptedException {
            return responseWritten.await(2, TimeUnit.SECONDS);
        }

        private boolean awaitCompletion() throws InterruptedException {
            boolean finished = complete.await(2, TimeUnit.SECONDS);
            Throwable error = failure.get();
            if (error != null) {
                throw new AssertionError("Fake Redis server failed", error);
            }
            return finished;
        }

        @Override
        public void close() throws IOException {
            serverSocket.close();
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
        StringBuilder result = new StringBuilder();
        int current;
        while ((current = input.read()) >= 0) {
            if (current == '\r') {
                if (input.read() != '\n') {
                    throw new IOException("Expected LF after CR");
                }
                return result.toString();
            }
            result.append((char) current);
        }
        throw new IOException("Unexpected end of command");
    }

    private static void expectCrLf(BufferedInputStream input) throws IOException {
        if (input.read() != '\r' || input.read() != '\n') {
            throw new IOException("Expected CRLF after Redis bulk command argument");
        }
    }

    private static void awaitClientClose(BufferedInputStream input) throws IOException {
        while (input.read() >= 0) {
            // The client owns the physical connection lifecycle.
        }
    }
}
