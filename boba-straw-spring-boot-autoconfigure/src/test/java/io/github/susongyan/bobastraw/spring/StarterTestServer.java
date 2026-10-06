package io.github.susongyan.bobastraw.spring;

import io.github.susongyan.bobastraw.protocol.RespCodec;
import io.github.susongyan.bobastraw.protocol.RespValue;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** RESP2-only loopback fixture with explicit socket ownership and no shared ephemeral binding. */
final class StarterTestServer implements AutoCloseable {
    private final ServerSocket server = new ServerSocket();
    private final List<Socket> sockets = new CopyOnWriteArrayList<Socket>();
    private final ExecutorService workers = Executors.newCachedThreadPool();
    final AtomicInteger commands = new AtomicInteger();
    final AtomicInteger active = new AtomicInteger();

    StarterTestServer() throws Exception {
        server.setReuseAddress(false);
        server.bind(new InetSocketAddress("127.0.0.1", 0));
        workers.submit(() -> {
            while (!server.isClosed()) {
                try {
                    Socket socket = server.accept();
                    sockets.add(socket);
                    workers.submit(() -> serve(socket));
                } catch (Exception error) {
                    if (!server.isClosed()) {
                        throw new AssertionError(error);
                    }
                }
            }
        });
    }

    String uri() {
        return "redis://127.0.0.1:" + server.getLocalPort();
    }

    private void serve(Socket socket) {
        active.incrementAndGet();
        try (Socket owned = socket) {
            RespCodec.Decoder decoder = new RespCodec.Decoder();
            byte[] buffer = new byte[4096];
            int length;
            while ((length = owned.getInputStream().read(buffer)) >= 0) {
                decoder.feed(buffer, length);
                RespValue command;
                while ((command = decoder.poll()) != null) {
                    commands.incrementAndGet();
                    String name = ((RespValue.Array) command).values.get(0).asString();
                    String response = "HELLO".equals(name) ? "-ERR unknown command 'HELLO'\r\n"
                        : "PING".equals(name) ? "+PONG\r\n" : "+OK\r\n";
                    owned.getOutputStream().write(response.getBytes(StandardCharsets.US_ASCII));
                }
            }
        } catch (Exception error) {
            if (!socket.isClosed()) {
                throw new AssertionError(error);
            }
        } finally {
            active.decrementAndGet();
        }
    }

    @Override
    public void close() throws Exception {
        server.close();
        for (Socket socket : sockets) {
            socket.close();
        }
        workers.shutdownNow();
        if (!workers.awaitTermination(5, TimeUnit.SECONDS)) {
            throw new AssertionError("Fixture workers did not terminate");
        }
    }
}
