package io.github.susongyan.bobastraw.benchmarks;

import io.github.susongyan.bobastraw.BobaStrawClient;
import io.github.susongyan.bobastraw.BobaStrawClientResources;
import io.github.susongyan.bobastraw.BobaStrawPipeline;
import io.github.susongyan.bobastraw.ProtocolVersion;
import io.github.susongyan.bobastraw.protocol.RespValue;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;

final class BenchmarkSupport {
    private BenchmarkSupport() {
    }

    static BobaStrawClient client(String endpoint, ProtocolVersion protocol) {
        return client(endpoint, protocol, null);
    }

    static BobaStrawClient client(
        String endpoint,
        ProtocolVersion protocol,
        BobaStrawClientResources resources
    ) {
        BobaStrawClient.Builder builder = BobaStrawClient.builder()
            .uri(endpoint)
            .protocol(protocol)
            .commandTimeout(Duration.ofSeconds(10));
        if (resources != null) {
            builder.resources(resources);
        }

        BobaStrawClient client = builder.build();
        try {
            String pong = client.sync().ping();
            if (!"PONG".equals(pong)) {
                throw new IllegalStateException("Benchmark Redis endpoint returned " + pong);
            }
            return client;
        } catch (RuntimeException failure) {
            close(failure, client);
            throw failure;
        } catch (Error failure) {
            close(failure, client);
            throw failure;
        }
    }

    static BobaStrawClient clientWithValue(
        String endpoint,
        ProtocolVersion protocol,
        String key,
        String value
    ) {
        BobaStrawClient client = client(endpoint, protocol);
        try {
            client.sync().set(key, value);
            return client;
        } catch (RuntimeException failure) {
            close(failure, client);
            throw failure;
        } catch (Error failure) {
            close(failure, client);
            throw failure;
        }
    }

    static String payload(int bytes) {
        char[] value = new char[bytes];
        Arrays.fill(value, 'b');
        return new String(value);
    }

    static List<RespValue> pipelineGet(BobaStrawClient client, String key, int commands) {
        BobaStrawPipeline pipeline = client.pipeline();
        for (int index = 0; index < commands; index++) {
            pipeline.command("GET", key);
        }
        return pipeline.execute().toCompletableFuture().join();
    }

    static Throwable close(Throwable failure, AutoCloseable closeable) {
        if (closeable == null) {
            return failure;
        }
        try {
            closeable.close();
        } catch (Throwable closeFailure) {
            return appendFailure(failure, closeFailure);
        }
        return failure;
    }

    static Throwable appendFailure(Throwable failure, Throwable additionalFailure) {
        if (additionalFailure == null) {
            return failure;
        }
        if (failure == null) {
            return additionalFailure;
        }
        if (failure != additionalFailure) {
            failure.addSuppressed(additionalFailure);
        }
        return failure;
    }

    static void throwIfFailed(Throwable failure) throws Exception {
        if (failure == null) {
            return;
        }
        if (failure instanceof Error) {
            throw (Error) failure;
        }
        if (failure instanceof Exception) {
            throw (Exception) failure;
        }
        throw new RuntimeException(failure);
    }
}
