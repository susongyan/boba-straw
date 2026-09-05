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

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(value = 2, jvmArgsAppend = { "-Xms512m", "-Xmx512m" })
@State(Scope.Benchmark)
public class SharedEventLoopFairnessBenchmark {
    @Param({ "redis://127.0.0.1:17379" })
    public String endpoint;

    @Param({ "AUTO" })
    public ProtocolVersion protocol;

    private final AtomicReference<Throwable> backgroundFailure =
        new AtomicReference<Throwable>();
    private final AtomicLong noisyCommandsCompleted = new AtomicLong();
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
        noisyCommandsCompleted.set(0L);
        forcedShutdown = false;
        try {
            resources = BobaStrawClientResources.builder().eventLoopThreads(1).build();
            noisyClient = BenchmarkSupport.client(endpoint, protocol, resources);
            healthyClient = BenchmarkSupport.client(endpoint, protocol, resources);
            noisyKey = "boba:benchmark:noisy";
            healthyKey = "boba:benchmark:healthy";
            noisyClient.sync().set(noisyKey, BenchmarkSupport.payload(1024));
            healthyClient.sync().set(healthyKey, "healthy");

            running = true;
            noisyThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    while (running) {
                        try {
                            BenchmarkSupport.pipelineGet(noisyClient, noisyKey, 128);
                            noisyCommandsCompleted.addAndGet(128L);
                        } catch (Throwable error) {
                            if (!forcedShutdown) {
                                backgroundFailure.compareAndSet(null, error);
                            }
                            return;
                        }
                    }
                }
            }, "boba-straw-benchmark-noisy-connection");
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
                            "Noisy benchmark thread did not finish within five seconds"
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
                                "Noisy benchmark thread survived client shutdown"
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
    public String healthyGetDuringNoisyPipeline(NoisyTrafficCounters counters) {
        try {
            Throwable failure = backgroundFailure.get();
            if (failure != null) {
                throw new IllegalStateException("Noisy benchmark connection failed", failure);
            }
            return healthyClient.sync().get(healthyKey);
        } finally {
            counters.observe(noisyCommandsCompleted.get());
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

    @AuxCounters(AuxCounters.Type.EVENTS)
    @State(Scope.Thread)
    public static class NoisyTrafficCounters {
        public long noisyCommands;

        private long observedCommands;

        @Setup(Level.Iteration)
        public void reset(SharedEventLoopFairnessBenchmark benchmark) {
            noisyCommands = 0L;
            observedCommands = benchmark.noisyCommandsCompleted.get();
        }

        private void observe(long completedCommands) {
            noisyCommands += completedCommands - observedCommands;
            observedCommands = completedCommands;
        }
    }
}
