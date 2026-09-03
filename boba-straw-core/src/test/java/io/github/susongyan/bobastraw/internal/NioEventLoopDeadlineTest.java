package io.github.susongyan.bobastraw.internal;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NioEventLoopDeadlineTest {
    @Test
    void runsDueDeadlineOnTheOwningEventLoop() throws Exception {
        NioEventLoop eventLoop = new NioEventLoop("boba-straw-deadline-test");
        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<String> threadName = new AtomicReference<String>();
        try {
            eventLoop.schedule(new Runnable() {
                @Override
                public void run() {
                    threadName.set(Thread.currentThread().getName());
                    completed.countDown();
                }
            }, TimeUnit.MILLISECONDS.toNanos(10L));

            assertTrue(completed.await(1, TimeUnit.SECONDS));
            assertTrue(threadName.get().startsWith("boba-straw-deadline-test"));
        } finally {
            eventLoop.requestShutdown();
            eventLoop.awaitTermination();
        }
    }

    @Test
    void cancelledDeadlineDoesNotRunOrTerminateTheEventLoop() throws Exception {
        NioEventLoop eventLoop = new NioEventLoop("boba-straw-cancelled-deadline-test");
        CountDownLatch cancelledAction = new CountDownLatch(1);
        CountDownLatch healthyAction = new CountDownLatch(1);
        try {
            NioEventLoop.ScheduledTask cancelled = eventLoop.schedule(new Runnable() {
                @Override
                public void run() {
                    cancelledAction.countDown();
                }
            }, TimeUnit.MILLISECONDS.toNanos(30L));
            assertTrue(cancelled.cancel());
            assertFalse(cancelled.cancel());

            eventLoop.schedule(new Runnable() {
                @Override
                public void run() {
                    healthyAction.countDown();
                }
            }, TimeUnit.MILLISECONDS.toNanos(10L));

            assertTrue(healthyAction.await(1, TimeUnit.SECONDS));
            assertFalse(cancelledAction.await(80, TimeUnit.MILLISECONDS));
            assertTrue(eventLoop.isOpen());
        } finally {
            eventLoop.requestShutdown();
            eventLoop.awaitTermination();
        }
    }
}
