package io.github.susongyan.bobastraw.internal;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Fixed-size owner of shared selector event loops. */
public final class NioEventLoopGroup implements AutoCloseable {
    private final NioEventLoop[] loops;
    private final AtomicInteger nextLoop = new AtomicInteger();
    private final AtomicBoolean closed = new AtomicBoolean();

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
        for (NioEventLoop loop : loops) {
            loop.requestShutdown();
        }
        for (NioEventLoop loop : loops) {
            loop.awaitTermination();
        }
    }
}
