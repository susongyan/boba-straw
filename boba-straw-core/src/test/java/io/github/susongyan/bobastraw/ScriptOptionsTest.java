package io.github.susongyan.bobastraw;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ScriptOptionsTest {
    @Test
    void defaultsAndImmutableSnapshots() {
        BobaStrawScriptOptions defaults = BobaStrawScriptOptions.defaults();
        assertEquals(1024, defaults.maxRegisteredScripts());
        assertEquals(16L * 1024L * 1024L, defaults.maxScriptBytes());
        assertEquals(4096, defaults.maxCacheHints());
        assertEquals(4096, defaults.maxInFlightExecutions());
        BobaStrawScriptOptions.Builder builder = BobaStrawScriptOptions.builder()
            .maxRegisteredScripts(2).maxScriptBytes(32).maxCacheHints(3).maxInFlightExecutions(4);
        BobaStrawScriptOptions options = builder.build();
        builder.maxRegisteredScripts(5).maxScriptBytes(64).maxCacheHints(6).maxInFlightExecutions(7);
        assertEquals(2, options.maxRegisteredScripts());
        assertEquals(32, options.maxScriptBytes());
        assertEquals(3, options.maxCacheHints());
        assertEquals(4, options.maxInFlightExecutions());
    }

    @Test
    void totalBodyBytesUseEncodedLengthWithoutEviction() {
        BobaStrawScripts scripts = new BobaStrawScripts(keys -> {
            throw new AssertionError("Registration must not connect");
        }, java.time.Duration.ofSeconds(1), true,
            BobaStrawScriptOptions.builder().maxScriptBytes(3).build());
        try {
            scripts.register("one", "茶", ScriptOutput.string());
            scripts.register("one", "茶", ScriptOutput.string());
            assertThrows(BobaStrawBackpressureException.class,
                () -> scripts.register("two", new byte[] {1}, ScriptOutput.bytes()));
            scripts.register("empty", new byte[0], ScriptOutput.bytes());
        } finally {
            scripts.close();
        }
    }

    @Test
    void rejectsInvalidLimitsAndNullOptionsOnEveryClientBuilder() {
        for (int value : new int[] {0, -1}) {
            assertThrows(IllegalArgumentException.class,
                () -> BobaStrawScriptOptions.builder().maxRegisteredScripts(value));
            assertThrows(IllegalArgumentException.class,
                () -> BobaStrawScriptOptions.builder().maxScriptBytes(value));
            assertThrows(IllegalArgumentException.class,
                () -> BobaStrawScriptOptions.builder().maxCacheHints(value));
            assertThrows(IllegalArgumentException.class,
                () -> BobaStrawScriptOptions.builder().maxInFlightExecutions(value));
        }
        assertThrows(IllegalArgumentException.class, () -> BobaStrawClient.builder().scriptOptions(null));
        assertThrows(IllegalArgumentException.class, () -> BobaStrawClusterClient.builder().scriptOptions(null));
        assertThrows(IllegalArgumentException.class, () -> BobaStrawSentinelClient.builder().scriptOptions(null));
    }
}
