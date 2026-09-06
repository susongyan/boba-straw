package io.github.susongyan.bobastraw.benchmarks;

import io.github.susongyan.bobastraw.BobaStrawBinaryCommands;
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

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(value = 2, jvmArgsAppend = { "-Xms1g", "-Xmx1g" })
@State(Scope.Benchmark)
public class RedisBinaryLargeValueBenchmark {
    @Param({ "redis://127.0.0.1:17379" })
    public String endpoint;

    @Param({ "AUTO" })
    public ProtocolVersion protocol;

    @Param({ "1024", "65536", "1048576" })
    public int valueBytes;

    private BobaStrawClient client;
    private BobaStrawBinaryCommands commands;
    private byte[] key;
    private byte[] value;

    @Setup
    public void setup() {
        key = ("boba:benchmark:binary-large:" + valueBytes)
            .getBytes(StandardCharsets.US_ASCII);
        value = new byte[valueBytes];
        Arrays.fill(value, (byte) 'b');
        client = BenchmarkSupport.client(endpoint, protocol);
        try {
            commands = client.binary();
            commands.set(key, value).toCompletableFuture().join();
        } catch (RuntimeException failure) {
            BenchmarkSupport.close(failure, client);
            throw failure;
        } catch (Error failure) {
            BenchmarkSupport.close(failure, client);
            throw failure;
        }
    }

    @TearDown
    public void tearDown() {
        client.close();
    }

    @Benchmark
    public byte[] get() {
        return commands.get(key).toCompletableFuture().join();
    }

    @Benchmark
    public byte[] set() {
        return commands.set(key, value).toCompletableFuture().join();
    }
}
