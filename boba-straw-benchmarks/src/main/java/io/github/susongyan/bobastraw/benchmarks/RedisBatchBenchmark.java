package io.github.susongyan.bobastraw.benchmarks;

import io.github.susongyan.bobastraw.BobaStrawClient;
import io.github.susongyan.bobastraw.ProtocolVersion;
import io.github.susongyan.bobastraw.protocol.RespValue;

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

import java.util.List;
import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(value = 2, jvmArgsAppend = { "-Xms512m", "-Xmx512m" })
@State(Scope.Benchmark)
public class RedisBatchBenchmark {
    @Param({ "redis://127.0.0.1:17379" })
    public String endpoint;

    @Param({ "AUTO" })
    public ProtocolVersion protocol;

    private BobaStrawClient client;
    private String key;

    @Setup
    public void setup() {
        key = "boba:benchmark:pipeline";
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
    @OperationsPerInvocation(1)
    public List<RespValue> pipeline1() {
        return BenchmarkSupport.pipelineGet(client, key, 1);
    }

    @Benchmark
    @OperationsPerInvocation(16)
    public List<RespValue> pipeline16() {
        return BenchmarkSupport.pipelineGet(client, key, 16);
    }

    @Benchmark
    @OperationsPerInvocation(128)
    public List<RespValue> pipeline128() {
        return BenchmarkSupport.pipelineGet(client, key, 128);
    }
}
