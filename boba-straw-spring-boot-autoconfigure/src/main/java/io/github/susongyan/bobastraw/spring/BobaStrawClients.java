package io.github.susongyan.bobastraw.spring;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.core.io.ResourceLoader;

/** Owns configured named clients, independently of the optional default client bean. */
public final class BobaStrawClients implements AutoCloseable {
    private final Map<String, AutoCloseable> clients = new LinkedHashMap<String, AutoCloseable>();
    private boolean closed;

    BobaStrawClients(Map<String, BobaStrawClientProperties> properties, ResourceLoader resources) {
        if (properties == null) {
            throw new IllegalArgumentException("Named clients must not be null");
        }
        for (Map.Entry<String, BobaStrawClientProperties> entry : properties.entrySet()) {
            if (!entry.getKey().matches("[a-zA-Z][a-zA-Z0-9_-]{0,63}")) {
                throw new IllegalArgumentException("Client names must be 1-64 letters, digits, '_' or '-'");
            }
            BobaStrawClientFactory.validate(entry.getValue());
        }
        try {
            BobaStrawClientFactory factory = new BobaStrawClientFactory(resources);
            for (Map.Entry<String, BobaStrawClientProperties> entry : properties.entrySet()) {
                clients.put(entry.getKey(), factory.create(entry.getValue()));
            }
        } catch (RuntimeException | Error failure) {
            try {
                close();
            } catch (RuntimeException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    /** The requested type must match the configured topology; no lossy common facade is introduced. */
    public <T extends AutoCloseable> T get(String name, Class<T> type) {
        AutoCloseable client = clients.get(name);
        if (client == null || !type.isInstance(client)) {
            throw new IllegalArgumentException("Unknown client name or incompatible client type");
        }
        return type.cast(client);
    }

    Map<String, AutoCloseable> entries() {
        return Collections.unmodifiableMap(clients);
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        RuntimeException failure = null;
        for (AutoCloseable client : clients.values()) {
            try {
                client.close();
            } catch (Exception error) {
                if (failure == null) {
                    failure = new IllegalStateException("Could not close configured clients");
                }
                failure.addSuppressed(error);
            }
        }
        if (failure != null) {
            throw failure;
        }
    }
}
