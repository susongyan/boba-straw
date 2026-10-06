package io.github.susongyan.bobastraw;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.PrintWriter;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/** Bounded real-server soak; opt-in, no restarts, FLUSH or business-command retries. */
@EnabledIfSystemProperty(named = "boba.straw.runSoak", matches = "true")
class StabilitySoakTest {
    @Test
    void persistentClientsPreserveResponsesAndReleaseResources() throws Exception {
        int seconds = Integer.getInteger("boba.straw.soakSeconds", 1800);
        assertTrue(seconds >= 10 && seconds <= 86400, "Duration must be 10..86400 seconds");
        Path samples = Paths.get(System.getProperty("boba.straw.soakSamples",
            "target/stability-samples.tsv"));
        Set<Long> originalThreads = clientThreads();
        List<BobaStrawClient> clients = new ArrayList<BobaStrawClient>();
        List<Future<?>> workers = new ArrayList<Future<?>>();
        ExecutorService executor = Executors.newFixedThreadPool(8);
        AtomicLong cycles = new AtomicLong();
        String prefix = "boba:soak:" + UUID.randomUUID() + ":";
        long started = System.nanoTime();
        try (PrintWriter output = new PrintWriter(Files.newBufferedWriter(samples,
            StandardCharsets.UTF_8))) {
            output.println("elapsed_s\tcycles\theap_used\tlive_threads\tclient_threads"
                + "\topen_fds\tinflight\tqueued_bytes\tconnection_creations\treconnects");
            for (int port = 16379; port <= 16382; port++) {
                for (ProtocolVersion protocol : new ProtocolVersion[] {
                    ProtocolVersion.RESP2, ProtocolVersion.AUTO
                }) {
                    BobaStrawClient client = BobaStrawClient.builder()
                        .endpoint("127.0.0.1", port).protocol(protocol)
                        .commandTimeout(Duration.ofSeconds(5)).build();
                    clients.add(client);
                    assertEquals("PONG", await(client.async().ping()));
                }
            }
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
            for (int index = 0; index < clients.size(); index++) {
                BobaStrawClient client = clients.get(index);
                String key = prefix + index;
                workers.add(executor.submit(() -> {
                    exercise(client, key, deadline, cycles);
                    return null;
                }));
            }
            do {
                for (Future<?> worker : workers) {
                    if (worker.isDone()) {
                        worker.get(); // Surface errors immediately, never replay a failed operation.
                    }
                }
                sample(output, started, cycles.get(), clients);
                if (workers.stream().allMatch(Future::isDone)) {
                    break;
                }
                Thread.sleep(1000);
            } while (System.nanoTime() < deadline + TimeUnit.SECONDS.toNanos(30));
            for (Future<?> worker : workers) {
                worker.get(10, TimeUnit.SECONDS);
            }
            for (BobaStrawClient client : clients) {
                assertEquals(0, client.metrics().inFlightCommands());
                assertEquals(0, client.metrics().queuedWriteBytes());
                assertEquals(0, client.metrics().reconnectAttempts(), "Unexpected reconnect in healthy soak");
                assertEquals(0, client.metrics().connectionBackpressureRejections());
            }
            sample(output, started, cycles.get(), clients);
            assertTrue(cycles.get() >= clients.size(), "Every worker must execute workload");
        } finally {
            for (Future<?> worker : workers) {
                worker.cancel(true);
            }
            executor.shutdownNow();
            try {
                assertTrue(executor.awaitTermination(15, TimeUnit.SECONDS), "Soak workers leaked");
            } finally {
                for (BobaStrawClient client : clients) {
                    client.close();
                }
            }
        }
        long closeDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        Set<Long> remaining;
        do {
            remaining = clientThreads();
            remaining.removeAll(originalThreads);
            if (remaining.isEmpty()) {
                break;
            }
            Thread.sleep(50);
        } while (System.nanoTime() < closeDeadline);
        assertTrue(remaining.isEmpty(), "Client threads survived close: " + remaining);
        System.out.println("Soak completed: seconds=" + seconds + ", cycles=" + cycles.get()
            + ", samples=" + samples + ", clientThreadsAfterClose=" + remaining.size());
    }

    private static void exercise(BobaStrawClient client, String key, long deadline,
                                 AtomicLong cycles) throws Exception {
        byte[] binaryKey = (key + ":binary").getBytes(StandardCharsets.UTF_8);
        String[] keys = {key + ":0", key + ":1", key + ":2", key + ":3"};
        Throwable failure = null;
        try {
            long iteration = 0;
            do {
                String value = key + ":" + iteration;
                List<CompletableFuture<String>> writes = new ArrayList<CompletableFuture<String>>();
                List<CompletableFuture<String>> reads = new ArrayList<CompletableFuture<String>>();
                for (String item : keys) {
                    writes.add(client.async().set(item, value).toCompletableFuture());
                    reads.add(client.async().get(item).toCompletableFuture());
                }
                for (CompletableFuture<String> write : writes) {
                    assertEquals("OK", await(write));
                }
                for (CompletableFuture<String> read : reads) {
                    assertEquals(value, await(read));
                }
                if (iteration % 10 == 0) {
                    BobaStrawPipeline pipeline = client.pipeline();
                    pipeline.typed().set(keys[0], value);
                    BobaStrawCommandHandle<String> read = pipeline.typed().get(keys[0]);
                    assertEquals(value, await(pipeline.executeTyped()).get(read));
                    try (BobaStrawTransaction transaction = client.transaction()) {
                        transaction.typed().set(keys[0], value);
                        BobaStrawCommandHandle<String> txRead = transaction.typed().get(keys[0]);
                        assertEquals(value, await(transaction.execTyped()).get(txRead));
                    }
                    byte[] bytes = {(byte) 0xff, 0, (byte) iteration, (byte) 0xfe};
                    assertArrayEquals(new byte[] {'O', 'K'}, await(client.binary().set(binaryKey, bytes)));
                    assertArrayEquals(bytes, await(client.binary().get(binaryKey)));
                }
                iteration++;
                cycles.incrementAndGet();
                Thread.sleep(100); // Bounded stability load, not a throughput benchmark.
            } while (System.nanoTime() < deadline && !Thread.currentThread().isInterrupted());
        } catch (Exception | AssertionError error) {
            failure = error;
            throw error;
        } finally {
            try {
                await(client.async().del(keys));
                await(client.binary().del(binaryKey));
            } catch (Exception | AssertionError cleanup) {
                if (failure != null) {
                    failure.addSuppressed(cleanup);
                } else {
                    throw cleanup;
                }
            }
        }
    }

    private static <T> T await(CompletionStage<T> stage) throws Exception {
        return stage.toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    private static Set<Long> clientThreads() {
        Set<Long> ids = new HashSet<Long>();
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            if (thread.isAlive() && thread.getName().startsWith("boba-straw-")) {
                ids.add(thread.getId());
            }
        }
        return ids;
    }

    private static void sample(PrintWriter output, long started, long cycles,
                               List<BobaStrawClient> clients) throws Exception {
        long inflight = 0;
        long queued = 0;
        long creations = 0;
        long reconnects = 0;
        for (BobaStrawClient client : clients) {
            BobaStrawClientMetrics metrics = client.metrics();
            inflight += metrics.inFlightCommands();
            queued += metrics.queuedWriteBytes();
            creations += metrics.connectionCreations();
            reconnects += metrics.reconnectAttempts();
        }
        Object fds;
        try {
            fds = ManagementFactory.getPlatformMBeanServer().getAttribute(
                new javax.management.ObjectName("java.lang:type=OperatingSystem"), "OpenFileDescriptorCount");
        } catch (javax.management.JMException unsupported) {
            fds = "unavailable";
        }
        output.println(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started) + "\t" + cycles
            + "\t" + ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed()
            + "\t" + ManagementFactory.getThreadMXBean().getThreadCount()
            + "\t" + clientThreads().size() + "\t" + fds + "\t" + inflight + "\t" + queued
            + "\t" + creations + "\t" + reconnects);
        output.flush();
        assertFalse(output.checkError(), "Failed to persist soak evidence");
    }
}
