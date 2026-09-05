package io.github.susongyan.bobastraw.benchmarks;

import io.github.susongyan.bobastraw.BobaStrawClient;
import io.github.susongyan.bobastraw.ProtocolVersion;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
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

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(value = 2, jvmArgsAppend = { "-Xms512m", "-Xmx512m" })
@State(Scope.Benchmark)
public class RedisCommandBenchmark {
    @Param({ "redis://127.0.0.1:17379" })
    public String endpoint;

    @Param({ "AUTO" })
    public ProtocolVersion protocol;

    private BobaStrawClient client;
    private String key;
    private String value;

    @Setup
    public void setup() {
        key = "boba:benchmark:command";
        value = BenchmarkSupport.payload(64);
        client = BenchmarkSupport.clientWithValue(endpoint, protocol, key, value);
    }

    @TearDown
    public void tearDown() {
        client.close();
    }

    @Benchmark
    public String syncGet() {
        return client.sync().get(key);
    }

    @Benchmark
    public String syncSet() {
        return client.sync().set(key, value);
    }

    @Benchmark
    public String asyncGetRoundTrip() {
        return client.async().get(key).toCompletableFuture().join();
    }

    @Benchmark
    public String asyncSetRoundTrip() {
        return client.async().set(key, value).toCompletableFuture().join();
    }
}
