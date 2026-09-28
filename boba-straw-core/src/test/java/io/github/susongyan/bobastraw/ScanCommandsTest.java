package io.github.susongyan.bobastraw;

import io.github.susongyan.bobastraw.protocol.RespValue;
import org.junit.jupiter.api.Test;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ScanCommandsTest {
    @Test
    void emptyNonTerminalPagePreservesUnsignedCursorAndOneRequest() throws Exception {
        AtomicInteger count = new AtomicInteger();
        String cursor = "18446744073709551615";
        BobaStrawScanCommands scan = new BobaStrawScanCommands((name, args) -> {
            count.incrementAndGet();
            assertEquals("SSCAN", name);
            assertArrayEquals(new String[] {"key", cursor, "MATCH", "", "COUNT", "2"}, args);
            return CompletableFuture.completedFuture(reply(cursor));
        }, false);
        ScanPage<String> page = scan.sscan("key", cursor, ScanArgs.none().match("").count(2))
            .toCompletableFuture().get();
        assertFalse(page.isFinished());
        assertEquals(cursor, page.cursor());
        assertTrue(page.values().isEmpty());
        assertEquals(1, count.get());
        assertThrows(UnsupportedOperationException.class, () -> scan.scan("0"));
        assertEquals(1, count.get());
    }

    @Test
    void optionsValidateBeforeSubmissionAndAreImmutable() {
        ScanArgs base = ScanArgs.none();
        base.match("x*").count(2);
        assertArrayEquals(new String[] {"0"}, base.arguments(null, "0"));
        for (String cursor : new String[] {null, "", "-1", "+1", " 0", "1a", "18446744073709551616"}) {
            assertThrows(IllegalArgumentException.class, () -> base.arguments(null, cursor));
        }
        assertThrows(IllegalArgumentException.class, () -> base.count(0));
        assertThrows(IllegalArgumentException.class, () -> base.match(null));
        BobaStrawScanCommands scan = new BobaStrawScanCommands((name, args) -> {
            fail("Invalid arguments must not be sent");
            return null;
        }, true);
        assertThrows(IllegalArgumentException.class, () -> scan.hscan(null, "0"));
        assertThrows(IllegalArgumentException.class, () -> scan.scan("0", null));
    }

    @Test
    void cancellationAndMalformedReplyDoNotCauseMoreRequests() {
        CompletableFuture<RespValue> source = new CompletableFuture<RespValue>();
        BobaStrawScanCommands scan = new BobaStrawScanCommands((name, args) -> source, true);
        assertTrue(scan.scan("0").toCompletableFuture().cancel(false));
        assertTrue(source.isCancelled());
        BobaStrawScanCommands malformed = new BobaStrawScanCommands((name, args) ->
            CompletableFuture.completedFuture(reply("0", new RespValue.SimpleString("odd"))), true);
        assertThrows(ExecutionException.class, () -> malformed.hscan("key", "0").toCompletableFuture().get());
        assertThrows(ExecutionException.class, () -> malformed.zscan("key", "0").toCompletableFuture().get());
    }

    @Test
    void duplicatesAndEmptyValuesAreNotDiscarded() throws Exception {
        BobaStrawScanCommands scan = new BobaStrawScanCommands((name, args) ->
            CompletableFuture.completedFuture(reply("0", new RespValue.SimpleString(""),
                new RespValue.SimpleString(""))), true);
        ScanPage<String> page = scan.scan("0").toCompletableFuture().get();
        assertTrue(page.isFinished());
        assertEquals(Arrays.asList("", ""), page.values());
        assertThrows(UnsupportedOperationException.class, () -> page.values().clear());
        assertEquals(Collections.singletonMap("", "").entrySet().iterator().next(),
            scan.hscan("key", "0").toCompletableFuture().get().values().get(0));
    }

    private static RespValue reply(String cursor, RespValue... values) {
        return new RespValue.Array(Arrays.asList(new RespValue.SimpleString(cursor),
            new RespValue.Array(Arrays.asList(values))));
    }
}
