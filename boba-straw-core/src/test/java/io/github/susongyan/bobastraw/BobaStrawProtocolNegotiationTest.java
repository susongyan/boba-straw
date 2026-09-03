package io.github.susongyan.bobastraw;

import io.github.susongyan.bobastraw.protocol.RespLimits;

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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BobaStrawProtocolNegotiationTest {
    @Test
    void autoFallsBackToResp2WhenHelloIsUnknown() throws Exception {
        FakeRedisServer server = new FakeRedisServer(new SessionHandler() {
            @Override
            public void handle(Session session) throws IOException {
                assertEquals(Arrays.asList("HELLO", "3"), session.readCommand());
                session.write("-ERR unknown command 'HELLO'\r\n");
                assertEquals(Arrays.asList("PING"), session.readCommand());
                session.write("+PONG\r\n");
                session.awaitClientClose();
            }
        });
        server.start();

        try (BobaStrawClient client = BobaStrawClient.builder()
            .endpoint("127.0.0.1", server.port())
            .protocol(ProtocolVersion.AUTO)
            .commandTimeout(Duration.ofSeconds(2))
            .build()) {
            assertEquals("PONG", client.sync().ping());
        }

        assertTrue(server.awaitCompletion());
        server.close();
    }

    @Test
    void explicitResp2DoesNotSendHello() throws Exception {
        FakeRedisServer server = new FakeRedisServer(new SessionHandler() {
            @Override
            public void handle(Session session) throws IOException {
                assertEquals(Arrays.asList("PING"), session.readCommand());
                session.write("+PONG\r\n");
                session.awaitClientClose();
            }
        });
        server.start();

        try (BobaStrawClient client = BobaStrawClient.builder()
            .endpoint("127.0.0.1", server.port())
            .protocol(ProtocolVersion.RESP2)
            .commandTimeout(Duration.ofSeconds(2))
            .build()) {
            assertEquals("PONG", client.sync().ping());
        }

        assertTrue(server.awaitCompletion());
        server.close();
    }

    @Test
    void attributeWrappedPushDoesNotConsumeTheNextCommandResponse() throws Exception {
        FakeRedisServer server = new FakeRedisServer(new SessionHandler() {
            @Override
            public void handle(Session session) throws IOException {
                assertEquals(Arrays.asList("PING"), session.readCommand());
                session.write("|1\r\n+source\r\n+cache\r\n>3\r\n+message\r\n+events\r\n+ignored\r\n+PONG\r\n");
                session.awaitClientClose();
            }
        });
        server.start();

        try (BobaStrawClient client = BobaStrawClient.builder()
            .endpoint("127.0.0.1", server.port())
            .protocol(ProtocolVersion.RESP2)
            .commandTimeout(Duration.ofSeconds(2))
            .build()) {
            assertEquals("PONG", client.sync().ping());
        }

        assertTrue(server.awaitCompletion());
        server.close();
    }

    @Test
    void cancellationDrainsItsResponseBeforeCompletingTheNextRequest() throws Exception {
        CountDownLatch commandsReceived = new CountDownLatch(1);
        CountDownLatch repliesAllowed = new CountDownLatch(1);
        FakeRedisServer server = new FakeRedisServer(new SessionHandler() {
            @Override
            public void handle(Session session) throws IOException {
                assertEquals(Arrays.asList("GET", "first"), session.readCommand());
                assertEquals(Arrays.asList("GET", "second"), session.readCommand());
                commandsReceived.countDown();
                await(repliesAllowed);
                session.write("$3\r\none\r\n$3\r\ntwo\r\n");
                session.awaitClientClose();
            }
        });
        server.start();

        try (BobaStrawClient client = BobaStrawClient.builder()
            .endpoint("127.0.0.1", server.port())
            .protocol(ProtocolVersion.RESP2)
            .commandTimeout(Duration.ofSeconds(2))
            .build()) {
            java.util.concurrent.CompletableFuture<String> first =
                client.async().get("first").toCompletableFuture();
            java.util.concurrent.CompletableFuture<String> second =
                client.async().get("second").toCompletableFuture();

            assertTrue(commandsReceived.await(2, TimeUnit.SECONDS));
            assertTrue(first.cancel(false));
            repliesAllowed.countDown();
            assertEquals("two", second.get(2, TimeUnit.SECONDS));
        }

        assertTrue(server.awaitCompletion());
        server.close();
    }

    @Test
    void pipelineCancellationPropagatesToConnectionResponseDraining() throws Exception {
        CountDownLatch commandsReceived = new CountDownLatch(1);
        CountDownLatch repliesAllowed = new CountDownLatch(1);
        FakeRedisServer server = new FakeRedisServer(new SessionHandler() {
            @Override
            public void handle(Session session) throws IOException {
                assertEquals(Arrays.asList("GET", "first"), session.readCommand());
                assertEquals(Arrays.asList("GET", "second"), session.readCommand());
                commandsReceived.countDown();
                await(repliesAllowed);
                session.write("$3\r\none\r\n$3\r\ntwo\r\n");
                assertEquals(Arrays.asList("GET", "after"), session.readCommand());
                session.write("$5\r\nthree\r\n");
                session.awaitClientClose();
            }
        });
        server.start();

        try (BobaStrawClient client = BobaStrawClient.builder()
            .endpoint("127.0.0.1", server.port())
            .protocol(ProtocolVersion.RESP2)
            .commandTimeout(Duration.ofSeconds(2))
            .build()) {
            java.util.concurrent.CompletableFuture<List<io.github.susongyan.bobastraw.protocol.RespValue>> pipeline =
                client.pipeline().command("GET", "first").command("GET", "second")
                    .execute().toCompletableFuture();

            assertTrue(commandsReceived.await(2, TimeUnit.SECONDS));
            assertTrue(pipeline.cancel(false));
            repliesAllowed.countDown();
            assertEquals("three", client.sync().get("after"));
        }

        assertTrue(server.awaitCompletion());
        server.close();
    }

    @Test
    void disconnectAfterWriteReportsThatTheCommandMayHaveExecuted() throws Exception {
        FakeRedisServer server = new FakeRedisServer(new SessionHandler() {
            @Override
            public void handle(Session session) throws IOException {
                assertEquals(Arrays.asList("GET", "value"), session.readCommand());
                // Closing after accepting the complete command makes its execution ambiguous.
            }
        });
        server.start();

        try (BobaStrawClient client = BobaStrawClient.builder()
            .endpoint("127.0.0.1", server.port())
            .protocol(ProtocolVersion.RESP2)
            .commandTimeout(Duration.ofSeconds(2))
            .build()) {
            try {
                client.async().get("value").toCompletableFuture().get(2, TimeUnit.SECONDS);
                throw new AssertionError("Expected the connection failure to reach the caller");
            } catch (java.util.concurrent.ExecutionException error) {
                assertTrue(error.getCause() instanceof BobaStrawCommandMayHaveExecutedException);
            }
        }

        assertTrue(server.awaitCompletion());
        server.close();
    }

    @Test
    void protocolLimitClosesTheConnectionAndPreservesAmbiguousDelivery() throws Exception {
        FakeRedisServer server = new FakeRedisServer(new SessionHandler() {
            @Override
            public void handle(Session session) throws IOException {
                assertEquals(Arrays.asList("GET", "value"), session.readCommand());
                session.write("$4\r\nmilk\r\n");
                session.awaitClientClose();
            }
        });
        server.start();

        RespLimits limits = RespLimits.builder()
            .maxBulkLength(3)
            .build();
        try (BobaStrawClient client = BobaStrawClient.builder()
            .endpoint("127.0.0.1", server.port())
            .protocol(ProtocolVersion.RESP2)
            .respLimits(limits)
            .commandTimeout(Duration.ofSeconds(2))
            .build()) {
            try {
                client.async().get("value").toCompletableFuture().get(2, TimeUnit.SECONDS);
                throw new AssertionError("Expected the protocol failure to reach the caller");
            } catch (java.util.concurrent.ExecutionException error) {
                assertTrue(error.getCause() instanceof BobaStrawCommandMayHaveExecutedException);
                assertTrue(error.getCause().getCause() instanceof BobaStrawProtocolException);
            }
        }

        assertTrue(server.awaitCompletion());
        server.close();
    }

    @Test
    void connectionFailureBeforeHandshakeReportsThatTheCommandWasNotSent() throws Exception {
        ServerSocket reservation = new ServerSocket(0);
        int unavailablePort = reservation.getLocalPort();
        reservation.close();

        try (BobaStrawClient client = BobaStrawClient.builder()
            .endpoint("127.0.0.1", unavailablePort)
            .protocol(ProtocolVersion.RESP2)
            .commandTimeout(Duration.ofSeconds(2))
            .build()) {
            try {
                client.async().get("value").toCompletableFuture().get(2, TimeUnit.SECONDS);
                throw new AssertionError("Expected the connection failure to reach the caller");
            } catch (java.util.concurrent.ExecutionException error) {
                assertTrue(error.getCause() instanceof BobaStrawCommandNotSentException);
            }
        }
    }

    @Test
    void resp3PubSubPushAcknowledgementCompletesSubscription() throws Exception {
        Resp3PubSubServer server = new Resp3PubSubServer();
        AtomicReference<String> message = new AtomicReference<String>();
        CountDownLatch messageReceived = new CountDownLatch(1);
        server.start();

        try (BobaStrawClient client = BobaStrawClient.builder()
            .endpoint("127.0.0.1", server.port())
            .protocol(ProtocolVersion.AUTO)
            .commandTimeout(Duration.ofSeconds(2))
            .build()) {
            BobaStrawSubscription subscription = client.pubSub().subscribe("events", value -> {
                message.set(value);
                messageReceived.countDown();
            }).toCompletableFuture().get(2, TimeUnit.SECONDS);

            assertTrue(messageReceived.await(2, TimeUnit.SECONDS));
            assertEquals("tea", message.get());
            subscription.close();
            assertTrue(server.awaitUnsubscribe());
        }

        assertTrue(server.awaitCompletion());
        server.close();
    }

    @Test
    void pubSubEstablishmentTimeoutClosesTheDedicatedConnection() throws Exception {
        Resp3PubSubServer server = new Resp3PubSubServer(false);
        server.start();

        try (BobaStrawClient client = BobaStrawClient.builder()
            .endpoint("127.0.0.1", server.port())
            .protocol(ProtocolVersion.AUTO)
            .commandTimeout(Duration.ofMillis(200))
            .build()) {
            java.util.concurrent.CompletableFuture<BobaStrawSubscription> subscription =
                client.pubSub().subscribe("events", ignored -> { }).toCompletableFuture();
            assertTrue(server.awaitSubscribe());
            try {
                subscription.get(2, TimeUnit.SECONDS);
                throw new AssertionError("Expected subscription establishment to time out");
            } catch (java.util.concurrent.ExecutionException error) {
                assertTrue(error.getCause() instanceof BobaStrawCommandTimeoutException);
            }
            assertTrue(server.awaitDedicatedClose());
        }

        assertTrue(server.awaitCompletion());
        server.close();
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

    private interface SessionHandler {
        void handle(Session session) throws IOException;
    }

    private static final class FakeRedisServer implements AutoCloseable {
        private final ServerSocket serverSocket;
        private final SessionHandler handler;
        private final CountDownLatch completed = new CountDownLatch(1);
        private volatile Throwable failure;

        private FakeRedisServer(SessionHandler handler) throws IOException {
            this.serverSocket = new ServerSocket(0);
            this.handler = handler;
        }

        private int port() {
            return serverSocket.getLocalPort();
        }

        private void start() {
            Thread thread = new Thread(new Runnable() {
                @Override
                public void run() {
                    try (Socket socket = serverSocket.accept()) {
                        handler.handle(new Session(socket));
                    } catch (Throwable error) {
                        failure = error;
                    } finally {
                        completed.countDown();
                    }
                }
            }, "fake-redis-server");
            thread.setDaemon(true);
            thread.start();
        }

        private boolean awaitCompletion() throws InterruptedException {
            boolean finished = completed.await(2, TimeUnit.SECONDS);
            if (failure != null) {
                throw new AssertionError("Fake Redis server failed", failure);
            }
            return finished;
        }

        @Override
        public void close() throws IOException {
            serverSocket.close();
        }
    }

    private static final class Resp3PubSubServer implements AutoCloseable {
        private final ServerSocket serverSocket = new ServerSocket(0);
        private final boolean acknowledge;
        private final CountDownLatch complete = new CountDownLatch(2);
        private final CountDownLatch subscribeReceived = new CountDownLatch(1);
        private final CountDownLatch unsubscribeReceived = new CountDownLatch(1);
        private final CountDownLatch dedicatedClose = new CountDownLatch(1);
        private final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();

        private Resp3PubSubServer() throws IOException {
            this(true);
        }

        private Resp3PubSubServer(boolean acknowledge) throws IOException {
            this.acknowledge = acknowledge;
        }

        private int port() {
            return serverSocket.getLocalPort();
        }

        private void start() {
            Thread acceptor = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        for (int index = 0; index < 2; index++) {
                            Socket socket = serverSocket.accept();
                            startSession(socket);
                        }
                    } catch (Throwable error) {
                        failure.compareAndSet(null, error);
                        while (complete.getCount() > 0) {
                            complete.countDown();
                        }
                    }
                }
            }, "fake-resp3-pubsub-acceptor");
            acceptor.setDaemon(true);
            acceptor.start();
        }

        private void startSession(final Socket socket) {
            Thread sessionThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    try (Socket ignored = socket) {
                        Session session = new Session(socket);
                        assertEquals(Arrays.asList("HELLO", "3"), session.readCommand());
                        session.write("+OK\r\n");
                        handleAfterHello(session);
                    } catch (Throwable error) {
                        failure.compareAndSet(null, error);
                    } finally {
                        complete.countDown();
                    }
                }
            }, "fake-resp3-pubsub-session");
            sessionThread.setDaemon(true);
            sessionThread.start();
        }

        private void handleAfterHello(Session session) throws IOException {
            List<String> command;
            try {
                command = session.readCommand();
            } catch (IOException ignored) {
                return;
            }
            if (!"SUBSCRIBE".equals(command.get(0))) {
                throw new IOException("Expected SUBSCRIBE but received " + command);
            }
            assertEquals(Arrays.asList("SUBSCRIBE", "events"), command);
            subscribeReceived.countDown();
            if (!acknowledge) {
                session.awaitClientClose();
                dedicatedClose.countDown();
                return;
            }
            session.write(">3\r\n+subscribe\r\n+events\r\n:1\r\n"
                + ">3\r\n+message\r\n+events\r\n+tea\r\n");
            assertEquals(Arrays.asList("UNSUBSCRIBE", "events"), session.readCommand());
            unsubscribeReceived.countDown();
            session.write(">3\r\n+unsubscribe\r\n+events\r\n:0\r\n");
            session.awaitClientClose();
        }

        private boolean awaitUnsubscribe() throws InterruptedException {
            return unsubscribeReceived.await(2, TimeUnit.SECONDS);
        }

        private boolean awaitSubscribe() throws InterruptedException {
            return subscribeReceived.await(2, TimeUnit.SECONDS);
        }

        private boolean awaitDedicatedClose() throws InterruptedException {
            return dedicatedClose.await(2, TimeUnit.SECONDS);
        }

        private boolean awaitCompletion() throws InterruptedException {
            boolean finished = complete.await(2, TimeUnit.SECONDS);
            Throwable error = failure.get();
            if (error != null) {
                throw new AssertionError("Fake RESP3 Pub/Sub server failed", error);
            }
            return finished;
        }

        @Override
        public void close() throws IOException {
            serverSocket.close();
        }
    }

    private static final class Session {
        private final BufferedInputStream input;
        private final BufferedOutputStream output;

        private Session(Socket socket) throws IOException {
            this.input = new BufferedInputStream(socket.getInputStream());
            this.output = new BufferedOutputStream(socket.getOutputStream());
        }

        private List<String> readCommand() throws IOException {
            int marker = input.read();
            if (marker != '*') {
                throw new IOException("Expected RESP array command");
            }
            int length = Integer.parseInt(readLine());
            List<String> command = new ArrayList<String>(length);
            for (int index = 0; index < length; index++) {
                if (input.read() != '$') {
                    throw new IOException("Expected RESP bulk string command argument");
                }
                int bytes = Integer.parseInt(readLine());
                byte[] value = new byte[bytes];
                int offset = 0;
                while (offset < bytes) {
                    int read = input.read(value, offset, bytes - offset);
                    if (read < 0) {
                        throw new IOException("Unexpected end of command");
                    }
                    offset += read;
                }
                expectCrLf();
                command.add(new String(value, StandardCharsets.UTF_8));
            }
            return command;
        }

        private void write(String response) throws IOException {
            output.write(response.getBytes(StandardCharsets.UTF_8));
            output.flush();
        }

        private void awaitClientClose() throws IOException {
            try {
                while (input.read() >= 0) {
                    // The client owns the connection lifecycle and closes after the assertion.
                }
            } catch (java.net.SocketException ignored) {
                // Once expected command and response assertions have completed, a peer TCP RST
                // is equivalent to EOF for this test fixture.
            }
        }

        private String readLine() throws IOException {
            StringBuilder line = new StringBuilder();
            int current;
            while ((current = input.read()) >= 0) {
                if (current == '\r') {
                    if (input.read() != '\n') {
                        throw new IOException("Expected LF after CR");
                    }
                    return line.toString();
                }
                line.append((char) current);
            }
            throw new IOException("Unexpected end of input");
        }

        private void expectCrLf() throws IOException {
            if (input.read() != '\r' || input.read() != '\n') {
                throw new IOException("Expected CRLF");
            }
        }
    }
}
