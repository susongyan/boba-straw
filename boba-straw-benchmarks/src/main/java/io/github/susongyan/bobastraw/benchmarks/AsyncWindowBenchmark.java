package io.github.susongyan.bobastraw.benchmarks;

import io.github.susongyan.bobastraw.BobaStrawClient;
import io.github.susongyan.bobastraw.ProtocolVersion;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
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

import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(value = 2, jvmArgsAppend = { "-Xms512m", "-Xmx512m" })
@State(Scope.Benchmark)
public class AsyncWindowBenchmark {
    @Param({ "redis://127.0.0.1:17379" })
    public String endpoint;

    @Param({ "AUTO" })
    public ProtocolVersion protocol;

    private BobaStrawClient client;
    private String key;
    private CompletionStage<String>[] window16;
    private CompletionStage<String>[] window128;
    private CompletionStage<String>[] window1024;

    @Setup
    public void setup() {
        key = "boba:benchmark:async-window";
        window16 = newWindow(16);
        window128 = newWindow(128);
        window1024 = newWindow(1024);
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
    @OperationsPerInvocation(16)
    public int asyncGetWindow16() {
        return executeWindow(window16);
    }

    @Benchmark
    @OperationsPerInvocation(128)
    public int asyncGetWindow128() {
        return executeWindow(window128);
    }

    @Benchmark
    @OperationsPerInvocation(1024)
    public int asyncGetWindow1024() {
        return executeWindow(window1024);
    }

    private int executeWindow(CompletionStage<String>[] requests) {
        int submitted = 0;
        try {
            for (; submitted < requests.length; submitted++) {
                requests[submitted] = client.async().get(key);
            }

            int decodedBytes = 0;
            for (int index = 0; index < submitted; index++) {
                decodedBytes += requests[index].toCompletableFuture().join().length();
            }
            return decodedBytes;
        } finally {
            for (int index = 0; index < submitted; index++) {
                requests[index] = null;
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static CompletionStage<String>[] newWindow(int size) {
        return (CompletionStage<String>[]) new CompletionStage<?>[size];
    }
}
