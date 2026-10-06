package io.github.susongyan.bobastraw.internal;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TlsTaskCapacityTest {
    @Test
    void tasksAreLazyBoundedAndOwnedByResources() throws Exception {
        CountDownLatch started = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        NioEventLoopGroup group = new NioEventLoopGroup(1);
        ThreadPoolExecutor executor = (ThreadPoolExecutor) group.tlsTasks();
        try {
            assertEquals(0, executor.getPoolSize());
            for (int index = 0; index < 2; index++) {
                executor.execute(() -> {
                    started.countDown();
                    try {
                        release.await();
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
            assertTrue(started.await(3, TimeUnit.SECONDS));
            for (int index = 0; index < 64; index++) {
                executor.execute(() -> { });
            }
            assertThrows(RejectedExecutionException.class, () -> executor.execute(() -> { }));
            assertEquals(2, executor.getPoolSize());
        } finally {
            group.close();
            release.countDown();
        }
        assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS));
        assertThrows(RejectedExecutionException.class, () -> executor.execute(() -> { }));
    }
}
