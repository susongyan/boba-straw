package io.github.susongyan.bobastraw;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "boba.straw.runCompatibility", matches = "true")
class ScanCompatibilityTest {
    @Test
    void scansAcrossServerAndProtocolMatrix() throws Exception {
        for (int port : new int[] {16379, 16380, 16381, 16382}) {
            for (ProtocolVersion protocol : new ProtocolVersion[] {ProtocolVersion.RESP2, ProtocolVersion.AUTO}) {
                try (BobaStrawClient client = BobaStrawClient.builder().endpoint("127.0.0.1", port)
                    .protocol(protocol).commandTimeout(Duration.ofSeconds(3)).build()) {
                    verify(client.async(), client.scan(), true);
                }
            }
        }
    }

    static void verify(BobaStrawAsyncCommands c, BobaStrawScanCommands scan, boolean database) throws Exception {
        String prefix = "boba-scan:{" + UUID.randomUUID() + "}:";
        String h = prefix + "h", s = prefix + "s", z = prefix + "z";
        ScanArgs options = ScanArgs.none().count(1);
        Set<String> expected = new HashSet<String>();
        try {
            assertTrue(await(scan.hscan(h, "0")).isFinished());
            assertTrue(await(scan.sscan(s, "0")).values().isEmpty());
            assertTrue(await(scan.zscan(z, "0")).values().isEmpty());
            for (int i = 0; i < 32; i++) {
                String member = "茶:" + i;
                expected.add(member);
                await(c.hset(h, member, "v" + i));
                await(c.sadd(s, member));
                await(c.zadd(z, i + 0.5, member));
            }
            assertEquals(expected, new HashSet<String>(collect(cursor -> scan.sscan(s, cursor, options))));
            Set<String> fields = new HashSet<String>();
            for (Map.Entry<String, String> entry : collect(cursor -> scan.hscan(h, cursor, options))) {
                fields.add(entry.getKey());
                assertEquals("v" + entry.getKey().substring(2), entry.getValue());
            }
            assertEquals(expected, fields);
            Set<String> members = new HashSet<String>();
            for (Map.Entry<String, Double> entry : collect(cursor -> scan.zscan(z, cursor, options))) {
                members.add(entry.getKey());
                assertEquals(Double.valueOf(Integer.parseInt(entry.getKey().substring(2)) + 0.5), entry.getValue());
            }
            assertEquals(expected, members);
            assertTrue(collect(cursor -> scan.sscan(s, cursor, options.match("absent:*"))).isEmpty());
            assertThrows(ExecutionException.class, () -> await(scan.hscan(s, "0")));
            if (database) {
                assertEquals(new HashSet<String>(Arrays.asList(h, s, z)), new HashSet<String>(
                    collect(cursor -> scan.scan(cursor, options.match(prefix + "*")))));
            } else {
                assertThrows(UnsupportedOperationException.class, () -> scan.scan("0"));
            }
            assertEquals(Long.valueOf(32), await(c.scard(s)));
        } finally {
            await(c.del(h, s, z));
        }
    }

    private static <T> List<T> collect(Function<String, CompletionStage<ScanPage<T>>> request) throws Exception {
        List<T> values = new ArrayList<T>();
        String cursor = "0";
        for (int calls = 0; calls < 10000; calls++) {
            ScanPage<T> page = await(request.apply(cursor));
            values.addAll(page.values());
            if (page.isFinished()) {
                return values;
            }
            cursor = page.cursor();
        }
        throw new AssertionError("Scan did not finish within the test safety bound");
    }

    private static <T> T await(CompletionStage<T> stage) throws Exception {
        return stage.toCompletableFuture().get(5, TimeUnit.SECONDS);
    }
}
