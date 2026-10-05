package io.github.susongyan.bobastraw;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;

/** Bind the exact address used by test clients, without sharing an ephemeral port. */
public final class LoopbackTestServer {
    private LoopbackTestServer() {
    }

    public static ServerSocket open() throws IOException {
        ServerSocket server = new ServerSocket();
        try {
            // A wildcard listener can coexist with a more specific loopback listener on macOS.
            server.setReuseAddress(false);
            server.bind(new InetSocketAddress("127.0.0.1", 0));
            return server;
        } catch (IOException error) {
            try {
                server.close();
            } catch (IOException closeError) {
                error.addSuppressed(closeError);
            }
            throw error;
        }
    }
}
