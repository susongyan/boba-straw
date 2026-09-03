package io.github.susongyan.bobastraw.internal;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BobaCallbackDispatcherTest {
    @Test
    void reservationBoundsOutstandingCallbacks() throws Exception {
        BobaCallbackDispatcher dispatcher = new BobaCallbackDispatcher(1, 1);
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch allowFirst = new CountDownLatch(1);
        CountDownLatch secondCompleted = new CountDownLatch(1);
        try {
            BobaCallbackDispatcher.Reservation first = dispatcher.tryReserve();
            BobaCallbackDispatcher.Reservation second = dispatcher.tryReserve();
            assertNotNull(first);
            assertNotNull(second);
            assertNull(dispatcher.tryReserve());

            assertTrue(first.dispatch(new Runnable() {
                @Override
                public void run() {
                    firstStarted.countDown();
                    await(allowFirst);
                }
            }));
            assertTrue(firstStarted.await(1, TimeUnit.SECONDS));
            assertTrue(second.dispatch(new Runnable() {
                @Override
                public void run() {
                    secondCompleted.countDown();
                }
            }));

            assertFalse(secondCompleted.await(80, TimeUnit.MILLISECONDS));
            allowFirst.countDown();
            assertTrue(secondCompleted.await(1, TimeUnit.SECONDS));

            BobaCallbackDispatcher.Reservation next = awaitReservation(dispatcher);
            assertNotNull(next);
            assertTrue(next.releaseIfUndispatched());
        } finally {
            allowFirst.countDown();
            dispatcher.close();
        }
    }

    @Test
    void serialDispatcherPreservesOneConnectionCallbackOrder() throws Exception {
        BobaCallbackDispatcher dispatcher = new BobaCallbackDispatcher(2, 4);
        BobaCallbackDispatcher.SerialDispatcher serial = dispatcher.serialDispatcher();
        List<Integer> observed = Collections.synchronizedList(new ArrayList<Integer>());
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch allowFirst = new CountDownLatch(1);
        CountDownLatch secondCompleted = new CountDownLatch(1);
        try {
            assertTrue(serial.execute(new Runnable() {
                @Override
                public void run() {
                    observed.add(1);
                    firstStarted.countDown();
                    await(allowFirst);
                }
            }));
            assertTrue(serial.execute(new Runnable() {
                @Override
                public void run() {
                    observed.add(2);
                    secondCompleted.countDown();
                }
            }));

            assertTrue(firstStarted.await(1, TimeUnit.SECONDS));
            assertFalse(secondCompleted.await(80, TimeUnit.MILLISECONDS));
            allowFirst.countDown();
            assertTrue(secondCompleted.await(1, TimeUnit.SECONDS));
            assertEquals(java.util.Arrays.asList(1, 2), observed);
        } finally {
            allowFirst.countDown();
            serial.close();
            dispatcher.close();
        }
    }

    @Test
    void closingSerialDispatcherSkipsQueuedListenerCallbacks() throws Exception {
        BobaCallbackDispatcher dispatcher = new BobaCallbackDispatcher(1, 4);
        BobaCallbackDispatcher.SerialDispatcher serial = dispatcher.serialDispatcher();
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch allowFirst = new CountDownLatch(1);
        CountDownLatch firstCompleted = new CountDownLatch(1);
        CountDownLatch secondCalled = new CountDownLatch(1);
        try {
            assertTrue(serial.execute(new Runnable() {
                @Override
                public void run() {
                    firstStarted.countDown();
                    await(allowFirst);
                    firstCompleted.countDown();
                }
            }));
            assertTrue(serial.execute(new Runnable() {
                @Override
                public void run() {
                    secondCalled.countDown();
                }
            }));

            assertTrue(firstStarted.await(1, TimeUnit.SECONDS));
            serial.close();
            allowFirst.countDown();
            assertTrue(firstCompleted.await(1, TimeUnit.SECONDS));
            assertFalse(secondCalled.await(100, TimeUnit.MILLISECONDS));
        } finally {
            allowFirst.countDown();
            serial.close();
            dispatcher.close();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(1, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting for callback test coordination");
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting for callback test coordination", error);
        }
    }

    private static BobaCallbackDispatcher.Reservation awaitReservation(
        BobaCallbackDispatcher dispatcher
    ) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1L);
        BobaCallbackDispatcher.Reservation reservation;
        while ((reservation = dispatcher.tryReserve()) == null && System.nanoTime() < deadline) {
            Thread.yield();
        }
        return reservation;
    }
}
