package io.github.susongyan.bobastraw.internal;

import io.github.susongyan.bobastraw.BobaStrawConnectionException;

import java.io.IOException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.HashSet;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/** One selector thread shared by a fixed set of physical Redis connections. */
final class NioEventLoop {
    private static final long SELECT_TIMEOUT_MILLIS = 100L;

    interface Task {
        void run();

        void reject(BobaStrawConnectionException error);
    }

    private final Selector selector;
    private final NioIoLimits ioLimits;
    private final ConcurrentLinkedQueue<Task> tasks = new ConcurrentLinkedQueue<Task>();
    private final Set<NioConnection> connections = new HashSet<NioConnection>();
    private final AtomicBoolean accepting = new AtomicBoolean(true);
    private final Thread thread;
    private volatile boolean shutdownRequested;
    private volatile boolean terminated;

    NioEventLoop(String name) {
        this(name, NioIoLimits.DEFAULT);
    }

    NioEventLoop(String name, NioIoLimits ioLimits) {
        if (ioLimits == null) {
            throw new IllegalArgumentException("ioLimits must not be null");
        }
        try {
            this.selector = Selector.open();
        } catch (IOException error) {
            throw new BobaStrawConnectionException("Could not open NIO selector", error);
        }
        this.ioLimits = ioLimits;
        this.thread = new Thread(new Runnable() {
            @Override
            public void run() {
                eventLoop();
            }
        }, name);
        this.thread.setDaemon(true);
        this.thread.start();
    }

    void execute(Task task) {
        if (!accepting.get()) {
            task.reject(closedError());
            return;
        }
        tasks.add(task);
        if (!accepting.get() && tasks.remove(task)) {
            task.reject(closedError());
            return;
        }
        selector.wakeup();
    }

    void register(NioConnection connection) {
        if (shutdownRequested || !accepting.get()) {
            connection.onRegistrationRejected(closedError());
            return;
        }
        connections.add(connection);
        try {
            connection.register(selector);
        } catch (Throwable error) {
            connection.onIoFailure(error);
        }
    }

    void detach(NioConnection connection) {
        connections.remove(connection);
    }

    boolean isOpen() {
        return accepting.get() && !terminated;
    }

    boolean isEventLoopThread() {
        return Thread.currentThread() == thread;
    }

    NioIoLimits ioLimits() {
        return ioLimits;
    }

    void requestShutdown() {
        if (!accepting.compareAndSet(true, false)) {
            return;
        }
        shutdownRequested = true;
        selector.wakeup();
    }

    void awaitTermination() {
        if (isEventLoopThread()) {
            return;
        }
        boolean interrupted = false;
        while (!terminated) {
            try {
                thread.join();
            } catch (InterruptedException error) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private void eventLoop() {
        BobaStrawConnectionException terminal = null;
        try {
            while (!shutdownRequested) {
                boolean tasksRemain = drainTasks();
                if (shutdownRequested) {
                    break;
                }
                if (tasksRemain || hasImmediateConnectionWork()) {
                    selector.selectNow();
                } else {
                    selector.select(SELECT_TIMEOUT_MILLIS);
                }
                if (shutdownRequested) {
                    break;
                }
                processSelectedKeys();
                tickConnections();
            }
            terminal = closedError();
        } catch (Throwable error) {
            terminal = error instanceof BobaStrawConnectionException
                ? (BobaStrawConnectionException) error
                : new BobaStrawConnectionException("NIO event loop failed", error);
        } finally {
            accepting.set(false);
            shutdownRequested = true;
            BobaStrawConnectionException error = terminal == null ? closedError() : terminal;
            closeConnections(error);
            rejectQueuedTasks(error);
            try {
                selector.close();
            } catch (IOException ignored) {
                // Closing the selector is best-effort.
            }
            terminated = true;
        }
    }

    private boolean drainTasks() {
        int processed = 0;
        Task task;
        while (processed < ioLimits.maxTasksPerTurn && (task = tasks.poll()) != null) {
            processed++;
            if (shutdownRequested) {
                task.reject(closedError());
                break;
            }
            try {
                task.run();
            } catch (Throwable error) {
                task.reject(new BobaStrawConnectionException("NIO event loop task failed", error));
            }
        }
        return !tasks.isEmpty();
    }

    private boolean hasImmediateConnectionWork() {
        for (NioConnection connection : connections) {
            if (connection.hasImmediateWork()) {
                return true;
            }
        }
        return false;
    }

    private void processSelectedKeys() {
        Iterator<SelectionKey> iterator = selector.selectedKeys().iterator();
        while (iterator.hasNext()) {
            SelectionKey key = iterator.next();
            iterator.remove();
            if (!key.isValid() || !(key.attachment() instanceof NioConnection)) {
                continue;
            }
            NioConnection connection = (NioConnection) key.attachment();
            try {
                connection.onSelected(key);
            } catch (Throwable error) {
                connection.onIoFailure(error);
            }
        }
    }

    private void tickConnections() {
        List<NioConnection> snapshot = new ArrayList<NioConnection>(connections);
        for (NioConnection connection : snapshot) {
            try {
                connection.onTick();
            } catch (Throwable error) {
                connection.onIoFailure(error);
            }
        }
    }

    private void closeConnections(BobaStrawConnectionException error) {
        List<NioConnection> snapshot = new ArrayList<NioConnection>(connections);
        connections.clear();
        for (NioConnection connection : snapshot) {
            connection.onEventLoopShutdown(error);
        }
    }

    private void rejectQueuedTasks(BobaStrawConnectionException error) {
        Task task;
        while ((task = tasks.poll()) != null) {
            task.reject(error);
        }
    }

    private BobaStrawConnectionException closedError() {
        return new BobaStrawConnectionException("Boba Straw EventLoop is closed");
    }
}
