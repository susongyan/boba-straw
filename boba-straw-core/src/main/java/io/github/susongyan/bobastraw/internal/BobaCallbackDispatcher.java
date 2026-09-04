package io.github.susongyan.bobastraw.internal;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Bounded executor for application-visible completions and listener callbacks.
 *
 * <p>A reservation is acquired before a request is sent, which makes completion dispatch
 * admission deterministic: a response that has a reservation never needs to run a user
 * callback on an EventLoop thread merely because the callback queue is full.</p>
 */
public final class BobaCallbackDispatcher implements AutoCloseable {
    private final ThreadPoolExecutor executor;
    private final Semaphore capacity;
    private final int queueCapacity;
    private final AtomicBoolean accepting = new AtomicBoolean(true);
    private final AtomicInteger nextThread = new AtomicInteger();

    public BobaCallbackDispatcher(int threads, int queueCapacity) {
        if (threads < 1) {
            throw new IllegalArgumentException("callbackThreads must be positive");
        }
        if (queueCapacity < 1) {
            throw new IllegalArgumentException("callbackQueueCapacity must be positive");
        }
        if (threads > Integer.MAX_VALUE - queueCapacity) {
            throw new IllegalArgumentException("callback capacity is too large");
        }
        this.queueCapacity = queueCapacity;
        this.capacity = new Semaphore(threads + queueCapacity);
        this.executor = new ThreadPoolExecutor(
            threads,
            threads,
            0L,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<Runnable>(queueCapacity),
            new ThreadFactory() {
                @Override
                public Thread newThread(Runnable action) {
                    Thread thread = new Thread(
                        action,
                        "boba-straw-callback-" + nextThread.getAndIncrement()
                    );
                    thread.setDaemon(true);
                    return thread;
                }
            },
            new ThreadPoolExecutor.AbortPolicy()
        );
    }

    public Reservation tryReserve() {
        if (!accepting.get() || !capacity.tryAcquire()) {
            return null;
        }
        if (!accepting.get()) {
            capacity.release();
            return null;
        }
        return new Reservation(this);
    }

    public SerialDispatcher serialDispatcher() {
        return new SerialDispatcher(this);
    }

    public boolean isOpen() {
        return accepting.get();
    }

    public int threadCount() {
        return executor.getCorePoolSize();
    }

    public int queueCapacity() {
        return queueCapacity;
    }

    @Override
    public void close() {
        if (accepting.compareAndSet(true, false)) {
            // Already accepted completion tasks drain so their callers are not left unresolved.
            executor.shutdown();
        }
    }

    private boolean dispatch(Reservation reservation, Runnable action) {
        try {
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        action.run();
                    } catch (Throwable ignored) {
                        // Application callbacks must never terminate a callback worker.
                    } finally {
                        reservation.releaseAfterExecution();
                    }
                }
            });
            return true;
        } catch (RejectedExecutionException ignored) {
            reservation.releaseAfterRejectedDispatch();
            return false;
        }
    }

    private void releaseCapacity() {
        capacity.release();
    }

    /** One bounded callback slot held until dispatch finishes or is explicitly abandoned. */
    public static final class Reservation {
        private static final int RESERVED = 0;
        private static final int DISPATCHED = 1;
        private static final int RELEASED = 2;

        private final BobaCallbackDispatcher dispatcher;
        private final AtomicInteger state = new AtomicInteger(RESERVED);

        private Reservation(BobaCallbackDispatcher dispatcher) {
            this.dispatcher = dispatcher;
        }

        public boolean dispatch(Runnable action) {
            if (action == null) {
                throw new IllegalArgumentException("callback action must not be null");
            }
            if (!state.compareAndSet(RESERVED, DISPATCHED)) {
                return false;
            }
            return dispatcher.dispatch(this, action);
        }

        /** Releases a slot when no callback has been queued for it yet. */
        public boolean releaseIfUndispatched() {
            if (state.compareAndSet(RESERVED, RELEASED)) {
                dispatcher.releaseCapacity();
                return true;
            }
            return false;
        }

        private void releaseAfterRejectedDispatch() {
            if (state.getAndSet(RELEASED) != RELEASED) {
                dispatcher.releaseCapacity();
            }
        }

        private void releaseAfterExecution() {
            if (state.getAndSet(RELEASED) != RELEASED) {
                dispatcher.releaseCapacity();
            }
        }
    }

    /** Preserves callback order for one connection while sharing the bounded worker pool. */
    public static final class SerialDispatcher implements AutoCloseable {
        private final BobaCallbackDispatcher dispatcher;
        private final Object lock = new Object();
        private final Queue<Entry> pending = new ArrayDeque<Entry>();
        private boolean dispatching;
        private boolean closed;

        private SerialDispatcher(BobaCallbackDispatcher dispatcher) {
            this.dispatcher = dispatcher;
        }

        /** Returns false without invoking {@code action} when callback capacity is exhausted. */
        public boolean execute(Runnable action) {
            if (action == null) {
                throw new IllegalArgumentException("callback action must not be null");
            }
            Reservation reservation = dispatcher.tryReserve();
            if (reservation == null) {
                return false;
            }

            boolean dispatchHead = false;
            synchronized (lock) {
                if (closed) {
                    reservation.releaseIfUndispatched();
                    return false;
                }
                pending.add(new Entry(reservation, action));
                if (!dispatching) {
                    dispatching = true;
                    dispatchHead = true;
                }
            }
            if (dispatchHead) {
                dispatchHead();
            }
            return true;
        }

        /**
         * Queues an internal barrier after callbacks already accepted for this serial stream.
         *
         * <p>The barrier reuses the worker executing the preceding entry, so it does not need a
         * new global callback reservation while the dispatcher is saturated. Returns false when
         * no callback precedes it or when this serial stream is already closed.</p>
         */
        public boolean executeBarrier(Runnable action) {
            if (action == null) {
                throw new IllegalArgumentException("callback action must not be null");
            }
            synchronized (lock) {
                if (closed || (!dispatching && pending.isEmpty())) {
                    return false;
                }
                pending.add(Entry.barrier(action));
                return true;
            }
        }

        @Override
        public void close() {
            synchronized (lock) {
                closed = true;
                for (Entry entry : pending) {
                    entry.cancelled = true;
                    if (entry.reservation != null) {
                        entry.reservation.releaseIfUndispatched();
                    }
                }
                pending.clear();
                dispatching = false;
            }
        }

        private void dispatchHead() {
            final Entry entry;
            synchronized (lock) {
                if (closed) {
                    dispatching = false;
                    return;
                }
                entry = pending.peek();
                if (entry == null) {
                    dispatching = false;
                    return;
                }
            }

            if (entry.barrier) {
                try {
                    if (!entry.cancelled) {
                        entry.action.run();
                    }
                } catch (Throwable ignored) {
                    // Internal lifecycle barriers must not terminate a callback worker.
                } finally {
                    onCompleted(entry);
                }
                return;
            }

            if (!entry.reservation.dispatch(new Runnable() {
                @Override
                public void run() {
                    try {
                        if (!entry.cancelled) {
                            entry.action.run();
                        }
                    } finally {
                        onCompleted(entry);
                    }
                }
            })) {
                onRejected(entry);
            }
        }

        private void onCompleted(Entry entry) {
            boolean dispatchNext;
            synchronized (lock) {
                pending.remove(entry);
                dispatchNext = !closed && !pending.isEmpty();
                if (!dispatchNext) {
                    dispatching = false;
                }
            }
            if (dispatchNext) {
                dispatchHead();
            }
        }

        private void onRejected(Entry rejected) {
            synchronized (lock) {
                pending.remove(rejected);
                for (Entry entry : pending) {
                    entry.cancelled = true;
                    if (entry.reservation != null) {
                        entry.reservation.releaseIfUndispatched();
                    }
                }
                pending.clear();
                closed = true;
                dispatching = false;
            }
        }

        private static final class Entry {
            private final Reservation reservation;
            private final Runnable action;
            private final boolean barrier;
            private volatile boolean cancelled;

            private Entry(Reservation reservation, Runnable action) {
                this.reservation = reservation;
                this.action = action;
                this.barrier = false;
            }

            private Entry(Runnable action) {
                this.reservation = null;
                this.action = action;
                this.barrier = true;
            }

            private static Entry barrier(Runnable action) {
                return new Entry(action);
            }
        }
    }
}
