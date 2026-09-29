package io.github.susongyan.bobastraw;

import io.github.susongyan.bobastraw.internal.EncodedCommand;
import io.github.susongyan.bobastraw.protocol.RespCodec;
import io.github.susongyan.bobastraw.protocol.RespValue;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ScriptCommandsTest {
    private static final String SHA = "0123456789abcdef0123456789abcdef01234567";

    @Test
    void textCommandsUseSharedExecutorAndDoNotGuessKeyPositions() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        BobaStrawAsyncCommands commands = new BobaStrawAsyncCommands((name, args) -> {
            int call = calls.getAndIncrement();
            if (call == 0) {
                assertEquals("SCRIPT", name);
                assertArrayEquals(new String[] {"LOAD", "return 7"}, args);
                return CompletableFuture.completedFuture(new RespValue.BlobString(ascii(SHA)));
            }
            assertEquals(call == 1 ? "EVAL" : "EVALSHA", name);
            assertArrayEquals(new String[] {call == 1 ? "return 7" : SHA,
                "2", "{a}:1", "{a}:2", "{b}:not-a-key"}, args);
            return CompletableFuture.completedFuture(new RespValue.Number(7));
        });
        assertEquals(SHA, commands.scriptLoad("return 7").toCompletableFuture().get());
        String[] keys = {"{a}:1", "{a}:2"};
        assertEquals(7, commands.eval("return 7", keys, "{b}:not-a-key").toCompletableFuture().get().asLong());
        assertEquals(Long.valueOf(7), commands.evalSha(SHA.toUpperCase(Locale.ROOT),
            ScriptOutput.integer(), keys, "{b}:not-a-key").toCompletableFuture().get());
        assertEquals(3, calls.get());
        assertEquals(Integer.valueOf(ClusterSlot.of(keys[0])), ClusterCommandRouting.slot("EVALSHA",
            new String[] {SHA, "2", keys[0], keys[1], "{b}:not-a-key"}));
        assertNull(ClusterCommandRouting.slot("EVAL", new String[] {"return 1", "0"}));
        assertThrows(IllegalArgumentException.class, () -> ClusterCommandRouting.slot("EVALSHA",
            new String[] {SHA, "2", "{a}", "{b}"}));
    }

    @Test
    void invalidArgumentsNeverReachTheExecutor() {
        BobaStrawAsyncCommands commands = new BobaStrawAsyncCommands((name, args) -> {
            fail("Invalid script invocation must not be submitted");
            return null;
        });
        assertThrows(IllegalArgumentException.class, () -> commands.eval(null, new String[0]));
        assertThrows(IllegalArgumentException.class, () -> commands.eval("", (String[]) null));
        assertThrows(IllegalArgumentException.class, () -> commands.eval("", new String[0], (String[]) null));
        assertThrows(IllegalArgumentException.class, () -> commands.eval("", new String[] {null}));
        assertThrows(IllegalArgumentException.class,
            () -> commands.eval("", (ScriptOutput<Long>) null, new String[0]));
        assertThrows(IllegalArgumentException.class, () -> commands.scriptLoad(null));
        for (String sha : new String[] {null, "", SHA.substring(1), SHA + "0", SHA.replace('0', 'g'),
            SHA.replace('0', '\uff10')}) {
            assertThrows(IllegalArgumentException.class, () -> commands.evalSha(sha, new String[0]));
        }
        BobaStrawBinaryCommands binary = BobaStrawBinaryCommands.withExecutor(frame -> {
            fail("Invalid binary script must not be submitted");
            return null;
        });
        assertThrows(IllegalArgumentException.class, () -> binary.eval(null, new byte[0][]));
        assertThrows(IllegalArgumentException.class, () -> binary.eval(new byte[0], new byte[][] {null}));
        assertThrows(IllegalArgumentException.class, () -> binary.eval(new byte[0], (byte[][]) null));
        assertThrows(IllegalArgumentException.class, () -> binary.evalSha("bad", new byte[0][]));
        assertThrows(IllegalArgumentException.class, () -> binary.scriptLoad(null));
    }

    @Test
    void scriptLoadHasFormMetadataWithoutAllowingScriptDebug() {
        assertNull(ClusterCommandRouting.slot("script", new String[] {"load", "return 1"}));
        assertEquals("2.6.0", CommandRegistry.resolve("SCRIPT", CommandArgs.text("LOAD", "")).since);
        assertNull(CommandRegistry.resolve("SCRIPT", CommandArgs.text("EXISTS", SHA)));
        assertThrows(IllegalArgumentException.class,
            () -> ClusterCommandRouting.explicitSlot(new String[] {"key"}, "SCRIPT", new String[] {"LOAD", ""}));
        assertThrows(IllegalArgumentException.class,
            () -> CommandRegistry.requireOrdinary("SCRIPT", CommandArgs.text("LOAD")));
        assertThrows(IllegalArgumentException.class,
            () -> CommandRegistry.requireOrdinary("SCRIPT", CommandArgs.text("LOAD", "", "extra")));
        for (String mode : new String[] {"YES", "SYNC", "NO"}) {
            assertThrows(IllegalArgumentException.class,
                () -> CommandRegistry.requireOrdinary("SCRIPT", CommandArgs.text("DEBUG", mode)));
            assertThrows(IllegalArgumentException.class,
                () -> CommandRegistry.requireTransaction("SCRIPT", CommandArgs.text("DEBUG", mode)));
            assertThrows(IllegalArgumentException.class, () -> TypedCommand.binary("SCRIPT",
                ScriptOutput.raw()::decode, ascii("DEBUG"), ascii(mode)));
        }
    }

    @Test
    void binaryFramesOwnExactScriptKeysAndArguments() throws Exception {
        AtomicReference<EncodedCommand> frame = new AtomicReference<EncodedCommand>();
        BobaStrawBinaryCommands binary = BobaStrawBinaryCommands.withExecutor(command -> {
            frame.set(command);
            return CompletableFuture.completedFuture(new RespValue.BlobString(new byte[] {(byte) 0xff, 0}));
        });
        byte[] script = ascii("return ARGV[1]");
        byte[] key = {(byte) 0xfe, 0};
        byte[] arg = {(byte) 0xff, 0};
        assertArrayEquals(arg, binary.eval(script, ScriptOutput.bytes(), new byte[][] {key}, arg)
            .toCompletableFuture().get());
        Arrays.fill(script, (byte) 'X');
        key[0] = 0;
        arg[0] = 0;
        RespValue.Array encoded = decode(frame.get());
        assertEquals("EVAL", encoded.values.get(0).asString());
        assertEquals("return ARGV[1]", encoded.values.get(1).asString());
        assertEquals("1", encoded.values.get(2).asString());
        assertArrayEquals(new byte[] {(byte) 0xfe, 0}, ScriptOutput.bytes().decode(encoded.values.get(3)));
        assertArrayEquals(new byte[] {(byte) 0xff, 0}, ScriptOutput.bytes().decode(encoded.values.get(4)));
        binary.evalSha(SHA, new byte[0][]).toCompletableFuture().get();
        assertEquals("EVALSHA", decode(frame.get()).values.get(0).asString());
        assertEquals(SHA, decode(frame.get()).values.get(1).asString());
    }

    @Test
    void cancellationPropagatesAndNoScriptOrDecodeFailureNeverReplays() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CompletableFuture<RespValue> source = new CompletableFuture<RespValue>();
        BobaStrawAsyncCommands commands = new BobaStrawAsyncCommands((name, args) -> {
            calls.incrementAndGet();
            return source;
        });
        assertTrue(commands.eval("return 1", ScriptOutput.integer(), new String[0])
            .toCompletableFuture().cancel(false));
        assertTrue(source.isCancelled());
        for (RuntimeException error : new RuntimeException[] {new BobaStrawServerException("NOSCRIPT absent"),
            new BobaStrawConnectionException("disconnected")}) {
            CompletableFuture<RespValue> failed = new CompletableFuture<RespValue>();
            failed.completeExceptionally(error);
            BobaStrawAsyncCommands failing = new BobaStrawAsyncCommands((name, args) -> {
                calls.incrementAndGet();
                return failed;
            });
            ExecutionException failure = assertThrows(ExecutionException.class,
                () -> failing.evalSha(SHA, new String[0]).toCompletableFuture().get());
            assertSame(error, failure.getCause());
        }
        BobaStrawAsyncCommands wrongType = new BobaStrawAsyncCommands((name, args) -> {
            calls.incrementAndGet();
            return CompletableFuture.completedFuture(new RespValue.SimpleString("not an integer"));
        });
        assertThrows(ExecutionException.class,
            () -> wrongType.eval("return 1", ScriptOutput.integer(), new String[0]).toCompletableFuture().get());
        assertEquals(4, calls.get());
    }

    @Test
    void loadReplyMustBeAValidDigest() throws Exception {
        TypedCommand<String> load = ScriptCommandFactory.load("");
        assertEquals(SHA, load.decoder().apply(new RespValue.BlobString(ascii(SHA))));
        assertThrows(IllegalStateException.class, () -> load.decoder().apply(RespValue.Null.INSTANCE));
        assertThrows(IllegalStateException.class, () -> load.decoder().apply(new RespValue.SimpleString("OK")));
    }

    private static RespValue.Array decode(EncodedCommand frame) {
        ByteBuffer buffer = frame.buffer();
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        RespCodec.Decoder decoder = new RespCodec.Decoder();
        decoder.feed(bytes, bytes.length);
        return (RespValue.Array) decoder.poll();
    }

    private static byte[] ascii(String text) {
        return text.getBytes(StandardCharsets.US_ASCII);
    }
}
