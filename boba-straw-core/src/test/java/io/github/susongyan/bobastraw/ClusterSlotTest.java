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
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClusterSlotTest {
    @Test
    void usesHashTagForRelatedKeys() {
        assertEquals(ClusterSlot.of("{user}:name"), ClusterSlot.of("{user}:profile"));
        assertNotEquals(ClusterSlot.of("user:1"), ClusterSlot.of("user:2"));
    }

    @Test
    void discoversFromAnyConfiguredSeed() throws Exception {
        ClusterSeedServer reachableSeed = new ClusterSeedServer();
        reachableSeed.start();

        ServerSocket unavailable = new ServerSocket(0);
        int unavailablePort = unavailable.getLocalPort();
        unavailable.close();

        try (BobaStrawClusterClient client = BobaStrawClusterClient.builder()
            .seeds("127.0.0.1:" + unavailablePort, "127.0.0.1:" + reachableSeed.port())
            .protocol(ProtocolVersion.RESP2)
            .commandTimeout(Duration.ofMillis(300))
            .build()) {
            assertEquals("PONG", client.executeAsync("PING").toCompletableFuture().join().asString());
        }

        assertTrue(reachableSeed.awaitCompletion());
        reachableSeed.close();
    }

    private static final class ClusterSeedServer implements AutoCloseable {
        private final ServerSocket serverSocket = new ServerSocket(0);
        private final CountDownLatch complete = new CountDownLatch(1);
        private final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();

        private ClusterSeedServer() throws IOException {
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
                        assertEquals(Arrays.asList("CLUSTER", "SLOTS"), readCommand(input));
                        write(output, clusterSlots(port()));
                        assertEquals(Arrays.asList("PING"), readCommand(input));
                        write(output, "+PONG\r\n");
                        awaitClientClose(input);
                    } catch (Throwable error) {
                        failure.set(error);
                    } finally {
                        complete.countDown();
                    }
                }
            }, "fake-cluster-seed");
            thread.setDaemon(true);
            thread.start();
        }

        private boolean awaitCompletion() throws InterruptedException {
            boolean finished = complete.await(2, TimeUnit.SECONDS);
            Throwable error = failure.get();
            if (error != null) {
                throw new AssertionError("Fake Cluster seed failed", error);
            }
            return finished;
        }

        @Override
        public void close() throws IOException {
            serverSocket.close();
        }

        private static List<String> readCommand(BufferedInputStream input) throws IOException {
            if (input.read() != '*') {
                throw new IOException("Expected RESP array");
            }
            int count = Integer.parseInt(readLine(input));
            List<String> command = new ArrayList<String>(count);
            for (int index = 0; index < count; index++) {
                if (input.read() != '$') {
                    throw new IOException("Expected RESP bulk string");
                }
                int length = Integer.parseInt(readLine(input));
                byte[] value = new byte[length];
                for (int offset = 0; offset < length;) {
                    int read = input.read(value, offset, length - offset);
                    if (read < 0) {
                        throw new IOException("Unexpected end of input");
                    }
                    offset += read;
                }
                expectCrLf(input);
                command.add(new String(value, StandardCharsets.UTF_8));
            }
            return command;
        }

        private static void write(BufferedOutputStream output, String response) throws IOException {
            output.write(response.getBytes(StandardCharsets.UTF_8));
            output.flush();
        }

        private static String clusterSlots(int port) {
            return "*1\r\n*3\r\n:0\r\n:16383\r\n*2\r\n$9\r\n127.0.0.1\r\n:"
                + port + "\r\n";
        }

        private static String readLine(BufferedInputStream input) throws IOException {
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

        private static void expectCrLf(BufferedInputStream input) throws IOException {
            if (input.read() != '\r' || input.read() != '\n') {
                throw new IOException("Expected CRLF");
            }
        }

        private static void awaitClientClose(BufferedInputStream input) throws IOException {
            while (input.read() >= 0) {
                // The client owns the physical connection lifecycle.
            }
        }
    }
}
