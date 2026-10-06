package io.github.susongyan.bobastraw.internal;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Fixed-size owner of shared selector event loops. */
public final class NioEventLoopGroup implements AutoCloseable {
    private final NioEventLoop[] loops;
    private final AtomicInteger nextLoop = new AtomicInteger();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final ExecutorService tlsTasks = new ThreadPoolExecutor(
        2, 2, 30L, TimeUnit.SECONDS, new ArrayBlockingQueue<Runnable>(64), action -> {
            Thread thread = new Thread(action, "boba-straw-tls-task");
            thread.setDaemon(true);
            return thread;
        }, new ThreadPoolExecutor.AbortPolicy());

    ExecutorService tlsTasks() {
        return tlsTasks;
    }

    public NioEventLoopGroup(int threads) {
        this(threads, NioIoLimits.DEFAULT);
    }

    NioEventLoopGroup(int threads, NioIoLimits ioLimits) {
        if (threads < 1) {
            throw new IllegalArgumentException("eventLoopThreads must be positive");
        }
        if (ioLimits == null) {
            throw new IllegalArgumentException("ioLimits must not be null");
        }
        this.loops = new NioEventLoop[threads];
        int created = 0;
        try {
            for (; created < threads; created++) {
                loops[created] = new NioEventLoop("boba-straw-nio-" + created, ioLimits);
            }
        } catch (RuntimeException error) {
            for (int index = 0; index < created; index++) {
                loops[index].requestShutdown();
            }
            for (int index = 0; index < created; index++) {
                loops[index].awaitTermination();
            }
            throw error;
        }
    }

    public int size() {
        return loops.length;
    }

    public boolean isOpen() {
        if (closed.get()) {
            return false;
        }
        for (NioEventLoop loop : loops) {
            if (!loop.isOpen()) {
                return false;
            }
        }
        return true;
    }

    NioEventLoop next() {
        int index = nextLoop.getAndIncrement() & Integer.MAX_VALUE;
        return loops[index % loops.length];
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        tlsTasks.shutdownNow();
        for (NioEventLoop loop : loops) {
            loop.requestShutdown();
        }
        for (NioEventLoop loop : loops) {
            loop.awaitTermination();
        }
    }
}
