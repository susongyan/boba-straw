package io.github.susongyan.bobastraw.internal;

import io.github.susongyan.bobastraw.BobaStrawConnectionException;

import java.io.IOException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** One selector thread shared by a fixed set of physical Redis connections. */
final class NioEventLoop {
    private static final long SELECT_TIMEOUT_MILLIS = 100L;

    interface Task {
        void run();

        void reject(BobaStrawConnectionException error);
    }

    /** Handle for a deadline that has not run yet. */
    interface ScheduledTask {
        boolean cancel();
    }

    private final Selector selector;
    private final NioIoLimits ioLimits;
    private final ConcurrentLinkedQueue<Task> tasks = new ConcurrentLinkedQueue<Task>();
    private final PriorityQueue<DeadlineTask> deadlines = new PriorityQueue<DeadlineTask>(
        11,
        new Comparator<DeadlineTask>() {
            @Override
            public int compare(DeadlineTask left, DeadlineTask right) {
                if (left.deadlineNanos < right.deadlineNanos) {
                    return -1;
                }
                if (left.deadlineNanos > right.deadlineNanos) {
                    return 1;
                }
                return left.sequence < right.sequence ? -1 : left.sequence == right.sequence ? 0 : 1;
            }
        }
    );
    private final Set<NioConnection> connections = new HashSet<NioConnection>();
    private final AtomicBoolean accepting = new AtomicBoolean(true);
    private final AtomicLong nextDeadlineSequence = new AtomicLong();
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

    ScheduledTask schedule(Runnable action, long delayNanos) {
        if (action == null) {
            throw new IllegalArgumentException("deadline action must not be null");
        }
        long delay = Math.max(0L, delayNanos);
        DeadlineTask deadline = new DeadlineTask(
            System.nanoTime() + delay,
            nextDeadlineSequence.getAndIncrement(),
            action
        );
        if (!accepting.get()) {
            deadline.cancel();
            return deadline;
        }
        execute(new Task() {
            @Override
            public void run() {
                if (!deadline.isCancelled()) {
                    deadlines.add(deadline);
                }
            }

            @Override
            public void reject(BobaStrawConnectionException error) {
                deadline.cancel();
            }
        });
        return deadline;
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
                boolean deadlinesRemain = runDueDeadlines();
                if (shutdownRequested) {
                    break;
                }
                if (tasksRemain || deadlinesRemain || hasImmediateConnectionWork()) {
                    selector.selectNow();
                } else {
                    selector.select(nextSelectTimeoutMillis());
                }
                if (shutdownRequested) {
                    break;
                }
                processSelectedKeys();
                runDueDeadlines();
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
            discardDeadlines();
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

    private boolean runDueDeadlines() {
        int processed = 0;
        long now = System.nanoTime();
        while (processed < ioLimits.maxTasksPerTurn) {
            DeadlineTask deadline = deadlines.peek();
            if (deadline == null || deadline.deadlineNanos - now > 0L) {
                break;
            }
            deadlines.remove();
            processed++;
            if (!deadline.tryRun()) {
                continue;
            }
            try {
                deadline.action.run();
            } catch (Throwable ignored) {
                // Deadline actions are internal. One failed action must not terminate the shared loop.
            }
            now = System.nanoTime();
        }
        discardCancelledDeadlineHeads();
        DeadlineTask next = deadlines.peek();
        return next != null && next.deadlineNanos - System.nanoTime() <= 0L;
    }

    private long nextSelectTimeoutMillis() {
        discardCancelledDeadlineHeads();
        DeadlineTask next = deadlines.peek();
        if (next == null) {
            return SELECT_TIMEOUT_MILLIS;
        }
        long remainingNanos = next.deadlineNanos - System.nanoTime();
        if (remainingNanos <= 0L) {
            return 0L;
        }
        long remainingMillis = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(remainingNanos);
        if (remainingMillis == 0L) {
            return 1L;
        }
        return Math.min(SELECT_TIMEOUT_MILLIS, remainingMillis);
    }

    private void discardCancelledDeadlineHeads() {
        while (!deadlines.isEmpty() && deadlines.peek().isCancelled()) {
            deadlines.remove();
        }
    }

    private void discardDeadlines() {
        while (!deadlines.isEmpty()) {
            deadlines.remove().cancel();
        }
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

    private final class DeadlineTask implements ScheduledTask {
        private final long deadlineNanos;
        private final long sequence;
        private final Runnable action;
        private final AtomicInteger state = new AtomicInteger();

        private DeadlineTask(long deadlineNanos, long sequence, Runnable action) {
            this.deadlineNanos = deadlineNanos;
            this.sequence = sequence;
            this.action = action;
        }

        @Override
        public boolean cancel() {
            if (state.compareAndSet(0, 1)) {
                selector.wakeup();
                return true;
            }
            return false;
        }

        private boolean tryRun() {
            return state.compareAndSet(0, 2);
        }

        private boolean isCancelled() {
            return state.get() == 1;
        }
    }
}
