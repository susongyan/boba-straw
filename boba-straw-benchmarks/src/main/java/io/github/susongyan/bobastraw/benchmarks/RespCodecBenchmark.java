package io.github.susongyan.bobastraw.benchmarks;

import io.github.susongyan.bobastraw.protocol.RespCodec;
import io.github.susongyan.bobastraw.protocol.RespValue;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(value = 2, jvmArgsAppend = { "-Xms512m", "-Xmx512m" })
@State(Scope.Thread)
public class RespCodecBenchmark {
    private RespCodec.Decoder decoder;
    private byte[] bulk64;
    private byte[] bulk64KiB;
    private byte[] fragmentedBulk;
    private byte[] responseBurst128;
    private byte[] resp3Aggregate;
    private byte[] oneByte;
    private String[] getCommand;

    @Setup
    public void setup() {
        decoder = new RespCodec.Decoder();
        bulk64 = bulk(64);
        bulk64KiB = bulk(64 * 1024);
        fragmentedBulk = bulk(1024);
        responseBurst128 = repeated("+PONG\r\n".getBytes(StandardCharsets.US_ASCII), 128);
        resp3Aggregate = ("%2\r\n+server\r\n+redis\r\n"
            + "+version\r\n+7.4.2\r\n").getBytes(StandardCharsets.US_ASCII);
        oneByte = new byte[1];
        getCommand = new String[] { "GET", "boba:benchmark:codec" };
    }

    @Benchmark
    public byte[] encodeGet() {
        return RespCodec.encodeCommand(getCommand);
    }

    @Benchmark
    public RespValue decodeBulk64() {
        return decode(bulk64);
    }

    @Benchmark
    public RespValue decodeBulk64KiB() {
        return decode(bulk64KiB);
    }

    @Benchmark
    public RespValue decodeResp3Aggregate() {
        return decode(resp3Aggregate);
    }

    @Benchmark
    @OperationsPerInvocation(128)
    public int decodeResponseBurst128() {
        decoder.feed(responseBurst128, responseBurst128.length);
        int decodedBytes = 0;
        for (int index = 0; index < 128; index++) {
            RespValue value = decoder.poll();
            if (value == null) {
                throw new IllegalStateException("RESP burst did not produce 128 values");
            }
            decodedBytes += value.asString().length();
        }
        return decodedBytes;
    }

    @Benchmark
    public RespValue decodeFragmentedBulkByteByByte() {
        RespValue value = null;
        for (byte current : fragmentedBulk) {
            oneByte[0] = current;
            decoder.feed(oneByte, 1);
            RespValue decoded = decoder.poll();
            if (decoded != null) {
                value = decoded;
            }
        }
        if (value == null) {
            throw new IllegalStateException("Fragmented RESP bulk did not complete");
        }
        return value;
    }

    private RespValue decode(byte[] input) {
        decoder.feed(input, input.length);
        RespValue value = decoder.poll();
        if (value == null) {
            throw new IllegalStateException("Complete RESP input did not produce a value");
        }
        return value;
    }

    private static byte[] bulk(int payloadBytes) {
        byte[] header = ("$" + payloadBytes + "\r\n").getBytes(StandardCharsets.US_ASCII);
        byte[] response = new byte[header.length + payloadBytes + 2];
        System.arraycopy(header, 0, response, 0, header.length);
        Arrays.fill(response, header.length, header.length + payloadBytes, (byte) 'b');
        response[response.length - 2] = '\r';
        response[response.length - 1] = '\n';
        return response;
    }

    private static byte[] repeated(byte[] value, int count) {
        byte[] result = new byte[value.length * count];
        for (int index = 0; index < count; index++) {
            System.arraycopy(value, 0, result, index * value.length, value.length);
        }
        return result;
    }
}
