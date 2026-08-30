package io.github.susongyan.bobastraw;

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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

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
            while (input.read() >= 0) {
                // The client owns the connection lifecycle and closes after the assertion.
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
