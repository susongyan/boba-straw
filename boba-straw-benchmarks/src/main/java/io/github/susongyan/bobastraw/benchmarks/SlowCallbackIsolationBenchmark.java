package io.github.susongyan.bobastraw.benchmarks;

import io.github.susongyan.bobastraw.BobaStrawClient;
import io.github.susongyan.bobastraw.BobaStrawClientResources;
import io.github.susongyan.bobastraw.ProtocolVersion;

import org.openjdk.jmh.annotations.AuxCounters;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Function;

@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(value = 2, jvmArgsAppend = { "-Xms512m", "-Xmx512m" })
@State(Scope.Benchmark)
public class SlowCallbackIsolationBenchmark {
    private static final long CALLBACK_DELAY_NANOS = TimeUnit.MILLISECONDS.toNanos(5);

    @Param({ "redis://127.0.0.1:17379" })
    public String endpoint;

    @Param({ "AUTO" })
    public ProtocolVersion protocol;

    private final AtomicReference<Throwable> backgroundFailure =
        new AtomicReference<Throwable>();
    private final AtomicLong noisyCompletions = new AtomicLong();
    private BobaStrawClientResources resources;
    private BobaStrawClient noisyClient;
    private BobaStrawClient healthyClient;
    private Thread noisyThread;
    private volatile boolean running;
    private volatile boolean forcedShutdown;
    private String noisyKey;
    private String healthyKey;

    @Setup
    public void setup() {
        backgroundFailure.set(null);
        noisyCompletions.set(0L);
        forcedShutdown = false;
        try {
            resources = BobaStrawClientResources.builder().eventLoopThreads(1).build();
            noisyClient = BenchmarkSupport.client(endpoint, protocol, resources);
            healthyClient = BenchmarkSupport.client(endpoint, protocol, resources);
            noisyKey = "boba:benchmark:slow-callback:noisy";
            healthyKey = "boba:benchmark:slow-callback:healthy";
            noisyClient.sync().set(noisyKey, "noisy");
            healthyClient.sync().set(healthyKey, "healthy");

            running = true;
            noisyThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    while (running) {
                        try {
                            CompletionStage<String> delayed = noisyClient.async().get(noisyKey)
                                .thenApply(new Function<String, String>() {
                                    @Override
                                    public String apply(String value) {
                                        blockCallbackThread();
                                        return value;
                                    }
                                });
                            delayed.toCompletableFuture().join();
                            noisyCompletions.incrementAndGet();
                        } catch (Throwable error) {
                            if (!forcedShutdown) {
                                backgroundFailure.compareAndSet(null, error);
                            }
                            return;
                        }
                    }
                }
            }, "boba-straw-benchmark-slow-callback");
            noisyThread.setDaemon(true);
            noisyThread.start();
        } catch (RuntimeException failure) {
            cleanupAfterSetupFailure(failure);
            throw failure;
        } catch (Error failure) {
            cleanupAfterSetupFailure(failure);
            throw failure;
        }
    }

    @TearDown
    public void tearDown() throws Exception {
        Throwable failure = null;
        boolean interrupted = false;
        running = false;
        try {
            if (noisyThread != null) {
                try {
                    noisyThread.join(TimeUnit.SECONDS.toMillis(5));
                } catch (InterruptedException error) {
                    interrupted = true;
                    failure = BenchmarkSupport.appendFailure(failure, error);
                }
                if (noisyThread.isAlive()) {
                    forcedShutdown = true;
                    failure = BenchmarkSupport.appendFailure(
                        failure,
                        new IllegalStateException(
                            "Slow-callback benchmark thread did not finish within five seconds"
                        )
                    );
                }
            }
        } finally {
            failure = BenchmarkSupport.close(failure, noisyClient);
            try {
                if (noisyThread != null && noisyThread.isAlive()) {
                    try {
                        noisyThread.join(TimeUnit.SECONDS.toMillis(1));
                    } catch (InterruptedException error) {
                        interrupted = true;
                        failure = BenchmarkSupport.appendFailure(failure, error);
                    }
                    if (noisyThread.isAlive()) {
                        failure = BenchmarkSupport.appendFailure(
                            failure,
                            new IllegalStateException(
                                "Slow-callback benchmark thread survived client shutdown"
                            )
                        );
                    }
                }
            } finally {
                failure = BenchmarkSupport.close(failure, healthyClient);
                failure = BenchmarkSupport.close(failure, resources);
            }
        }

        failure = BenchmarkSupport.appendFailure(failure, backgroundFailure.get());
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
        BenchmarkSupport.throwIfFailed(failure);
    }

    @Benchmark
    public String healthyGetDuringSlowCallback(NoisyCallbackCounters counters) {
        try {
            Throwable failure = backgroundFailure.get();
            if (failure != null) {
                throw new IllegalStateException("Slow-callback benchmark connection failed", failure);
            }
            return healthyClient.sync().get(healthyKey);
        } finally {
            counters.observe(noisyCompletions.get());
        }
    }

    private void cleanupAfterSetupFailure(Throwable failure) {
        running = false;
        forcedShutdown = true;
        if (noisyThread != null) {
            noisyThread.interrupt();
        }
        BenchmarkSupport.close(failure, healthyClient);
        BenchmarkSupport.close(failure, noisyClient);
        BenchmarkSupport.close(failure, resources);
    }

    private static void blockCallbackThread() {
        long deadline = System.nanoTime() + CALLBACK_DELAY_NANOS;
        long remaining;
        while ((remaining = deadline - System.nanoTime()) > 0L) {
            LockSupport.parkNanos(remaining);
        }
    }

    @AuxCounters(AuxCounters.Type.EVENTS)
    @State(Scope.Thread)
    public static class NoisyCallbackCounters {
        public long noisyCompletions;

        private long observedCompletions;

        @Setup(Level.Iteration)
        public void reset(SlowCallbackIsolationBenchmark benchmark) {
            noisyCompletions = 0L;
            observedCompletions = benchmark.noisyCompletions.get();
        }

        private void observe(long completedCallbacks) {
            noisyCompletions += completedCallbacks - observedCompletions;
            observedCompletions = completedCallbacks;
        }
    }
}
