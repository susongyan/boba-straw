package io.github.susongyan.bobastraw.benchmarks;

import io.github.susongyan.bobastraw.BobaStrawClient;
import io.github.susongyan.bobastraw.BobaStrawClientMetrics;
import io.github.susongyan.bobastraw.ProtocolVersion;
import io.github.susongyan.bobastraw.protocol.RespValue;

import org.openjdk.jmh.annotations.AuxCounters;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

/** Dedicated capacity run that records transport syscall and byte ratios. */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 5)
@Fork(value = 1, jvmArgsAppend = { "-Xms512m", "-Xmx512m" })
@State(Scope.Benchmark)
public class TransportObservationBenchmark {
    private static final int ASYNC_WINDOW = 1024;
    private static final int PIPELINE_SIZE = 128;

    @Param({ "redis://127.0.0.1:17379" })
    public String endpoint;

    @Param({ "AUTO" })
    public ProtocolVersion protocol;

    private BobaStrawClient client;
    private CompletionStage<String>[] window;
    private String key;

    @Setup
    public void setup() {
        key = "boba:benchmark:transport-observation";
        window = newWindow(ASYNC_WINDOW);
        client = BenchmarkSupport.clientWithValue(
            endpoint,
            protocol,
            key,
            BenchmarkSupport.payload(64)
        );
    }

    @TearDown
    public void tearDown() {
        client.close();
    }

    @Benchmark
    @OperationsPerInvocation(ASYNC_WINDOW)
    public int asyncWindow1024(TransportCounters counters) {
        int submitted = 0;
        try {
            for (; submitted < window.length; submitted++) {
                window[submitted] = client.async().get(key);
            }
            int decodedBytes = 0;
            for (int index = 0; index < submitted; index++) {
                decodedBytes += window[index].toCompletableFuture().join().length();
            }
            return decodedBytes;
        } finally {
            for (int index = 0; index < submitted; index++) {
                window[index] = null;
            }
            counters.observe(client.metrics(), submitted);
        }
    }

    @Benchmark
    @OperationsPerInvocation(PIPELINE_SIZE)
    public List<RespValue> pipeline128(TransportCounters counters) {
        List<RespValue> result = BenchmarkSupport.pipelineGet(client, key, PIPELINE_SIZE);
        counters.observe(client.metrics(), PIPELINE_SIZE);
        return result;
    }

    @SuppressWarnings("unchecked")
    private static CompletionStage<String>[] newWindow(int size) {
        return (CompletionStage<String>[]) new CompletionStage<?>[size];
    }

    @AuxCounters(AuxCounters.Type.EVENTS)
    @State(Scope.Thread)
    public static class TransportCounters {
        public long commands;
        public long socketReadOperations;
        public long socketBytesRead;
        public long socketWriteOperations;
        public long socketBytesWritten;

        private long observedReadOperations;
        private long observedBytesRead;
        private long observedWriteOperations;
        private long observedBytesWritten;

        @Setup(Level.Iteration)
        public void reset(TransportObservationBenchmark benchmark) {
            commands = 0L;
            socketReadOperations = 0L;
            socketBytesRead = 0L;
            socketWriteOperations = 0L;
            socketBytesWritten = 0L;
            BobaStrawClientMetrics metrics = benchmark.client.metrics();
            observedReadOperations = metrics.socketReadOperations();
            observedBytesRead = metrics.socketBytesRead();
            observedWriteOperations = metrics.socketWriteOperations();
            observedBytesWritten = metrics.socketBytesWritten();
        }

        private void observe(BobaStrawClientMetrics metrics, int commandCount) {
            commands += commandCount;
            socketReadOperations += metrics.socketReadOperations() - observedReadOperations;
            socketBytesRead += metrics.socketBytesRead() - observedBytesRead;
            socketWriteOperations += metrics.socketWriteOperations() - observedWriteOperations;
            socketBytesWritten += metrics.socketBytesWritten() - observedBytesWritten;
            observedReadOperations = metrics.socketReadOperations();
            observedBytesRead = metrics.socketBytesRead();
            observedWriteOperations = metrics.socketWriteOperations();
            observedBytesWritten = metrics.socketBytesWritten();
        }
    }
}
