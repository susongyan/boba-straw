package io.github.susongyan.bobastraw.internal;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Lazy, bounded pool for stateful transaction connections. */
public final class TransactionConnectionPool implements AutoCloseable {
    private final String host;
    private final int port;
    private final Duration timeout;
    private final io.github.susongyan.bobastraw.ProtocolVersion protocol;
    private final String username;
    private final String password;
    private final String clientName;
    private final int maxSize;
    private final Duration acquireTimeout;
    private final Duration idleTimeout;
    private final Deque<NioConnection> idle = new ArrayDeque<NioConnection>();
    private final Set<NioConnection> active = new HashSet<NioConnection>();
    private int created;
    private boolean closed;
    private final ScheduledExecutorService reaper;

    public TransactionConnectionPool(
        String host,
        int port,
        Duration timeout,
        io.github.susongyan.bobastraw.ProtocolVersion protocol,
        String username,
        String password,
        String clientName,
        int maxSize,
        Duration acquireTimeout,
        Duration idleTimeout
    ) {
        if (maxSize < 1) {
            throw new IllegalArgumentException("maxSize must be positive");
        }
        this.host = host;
        this.port = port;
        this.timeout = timeout;
        this.protocol = protocol;
        this.username = username;
        this.password = password;
        this.clientName = clientName;
        this.maxSize = maxSize;
        this.acquireTimeout = acquireTimeout;
        this.idleTimeout = idleTimeout;
        this.reaper = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "boba-straw-transaction-reaper");
            thread.setDaemon(true);
            return thread;
        });
        long period = Math.max(1L, idleTimeout.toMillis());
        reaper.scheduleAtFixedRate(this::reapIdle, period, period, TimeUnit.MILLISECONDS);
    }

    public synchronized NioConnection acquire() {
        if (closed) {
            throw new IllegalStateException("Transaction pool is closed");
        }
        long deadline = System.nanoTime() + acquireTimeout.toNanos();
        while (true) {
            IdleConnection available = idle.pollFirst();
            if (available != null) {
                NioConnection connection = available.connection;
                if (!connection.isOpen()) {
                    created--;
                    connection.close();
                    continue;
                }
                active.add(connection);
                return connection;
            }
            if (created < maxSize) {
                created++;
                NioConnection connection = new NioConnection(
                    host, port, timeout, protocol, username, password, clientName
                );
                active.add(connection);
                return connection;
            }
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0L) {
                throw new IllegalStateException("Timed out acquiring transaction connection");
            }
            try {
                TimeUnit.NANOSECONDS.timedWait(this, remaining);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted acquiring transaction connection", interrupted);
            }
        }
    }

    public synchronized void release(NioConnection connection) {
        if (closed) {
            connection.close();
            return;
        }
        active.remove(connection);
        if (!connection.isOpen()) {
            created--;
            connection.close();
        } else {
            idle.addLast(new IdleConnection(connection));
        }
        notifyAll();
    }

    public synchronized void destroy(NioConnection connection) {
        created--;
        active.remove(connection);
        connection.close();
    }

    @Override
    public synchronized void close() {
        closed = true;
        while (!idle.isEmpty()) {
            idle.removeFirst().connection.close();
            created--;
        }
        for (NioConnection connection : active) {
            connection.close();
            created--;
        }
        active.clear();
        reaper.shutdownNow();
        notifyAll();
    }

    private synchronized void reapIdle() {
        if (closed) {
            return;
        }
        long now = System.nanoTime();
        while (!idle.isEmpty()) {
            IdleConnection candidate = idle.peekFirst();
            if (now - candidate.returnedAtNanos < idleTimeout.toNanos()) {
                break;
            }
            idle.removeFirst();
            created--;
            candidate.connection.close();
        }
        notifyAll();
    }

    private static final class IdleConnection {
        private final NioConnection connection;
        private final long returnedAtNanos = System.nanoTime();

        private IdleConnection(NioConnection connection) {
            this.connection = connection;
        }
    }
}
