package io.github.susongyan.bobastraw;

import io.github.susongyan.bobastraw.protocol.RespValue;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "boba.straw.runCompatibility", matches = "true")
class ScriptCompatibilityTest {
    private static final String INCREMENT = "return redis.call('INCRBY', KEYS[1], ARGV[1])";

    @Test
    void luaCommandsWorkAcrossServersAndProtocols() throws Exception {
        for (int port : new int[] {16379, 16380, 16381, 16382}) {
            for (ProtocolVersion protocol : new ProtocolVersion[] {ProtocolVersion.RESP2, ProtocolVersion.AUTO}) {
                try (BobaStrawClient client = BobaStrawClient.builder().endpoint("127.0.0.1", port)
                    .protocol(protocol).commandTimeout(Duration.ofSeconds(3)).build()) {
                    verifyAsync(client.async(), (key, script) -> client.async().scriptLoad(script));
                    verifySyncAndBinary(client);
                    verifyRegistered(client.scripts(), client.async());
                    client.scripts().register("binary-echo", "return ARGV[1]", ScriptOutput.bytes());
                    for (int index = 0; index < 2; index++) {
                        byte[] bytes = {(byte) 0xff, 0, (byte) 0xfe};
                        assertArrayEquals(bytes, await(client.scripts().executeBinary("binary-echo",
                            ScriptOutput.bytes(), new byte[0][], bytes)));
                    }
                }
            }
        }
    }

    static void verifyRegistered(BobaStrawScripts scripts, BobaStrawAsyncCommands commands) throws Exception {
        String key = "boba-registered:{" + UUID.randomUUID() + "}:counter";
        scripts.register("increment", INCREMENT, ScriptOutput.integer());
        scripts.register("increment", INCREMENT, ScriptOutput.integer());
        try {
            assertNull(await(commands.get(key)), "Registration is local only");
            for (long count = 1; count <= 3; count++) {
                assertEquals(Long.valueOf(count), await(scripts.execute("increment",
                    ScriptOutput.integer(), new String[] {key}, "1")));
            }
            assertEquals("3", await(commands.get(key)));
            scripts.register("empty", "return false", ScriptOutput.string());
            assertNull(await(scripts.execute("empty", ScriptOutput.string(), new String[0])));
            assertNull(await(scripts.execute("empty", ScriptOutput.string(), new String[0])));
        } finally {
            await(commands.del(key));
        }
    }

    static void verifyAsync(BobaStrawAsyncCommands commands,
                            BiFunction<String, String, CompletionStage<String>> loader) throws Exception {
        String key = "boba-lua:{" + UUID.randomUUID() + "}:counter";
        try {
            String sha = await(loader.apply(key, INCREMENT));
            assertEquals(digest(INCREMENT.getBytes(StandardCharsets.UTF_8)), sha);
            assertNull(await(commands.get(key)), "Loading must not execute the script");
            assertEquals(sha, await(loader.apply(key, INCREMENT)));
            assertNull(await(commands.get(key)));
            assertEquals(Long.valueOf(2), await(commands.evalSha(sha, ScriptOutput.integer(), new String[] {key}, "2")));
            assertEquals(5, await(commands.eval(INCREMENT, new String[] {key}, "3")).asLong());

            String nested = "return {ARGV[1], {KEYS[1], false}, 7}";
            RespValue.Array raw = (RespValue.Array) await(commands.eval(nested, new String[] {key}, "茶"));
            assertEquals("茶", raw.values.get(0).asString());
            assertNull(((RespValue.Array) raw.values.get(1)).values.get(1).asString());
            assertEquals(Arrays.asList("茶", ""), await(commands.eval("return {ARGV[1], ARGV[2]}",
                ScriptOutput.list(ScriptOutput.string()), new String[0], "茶", "")));
            assertNull(await(commands.eval("return false", ScriptOutput.integer(), new String[0])));
            assertTrue(await(commands.eval("return {}", ScriptOutput.list(ScriptOutput.integer()), new String[0])).isEmpty());

            ExecutionException absent = assertThrows(ExecutionException.class, () -> await(commands.evalSha(
                "0000000000000000000000000000000000000000", new String[] {key})));
            assertTrue(absent.getCause() instanceof BobaStrawServerException);
            assertTrue(absent.getCause().getMessage().startsWith("NOSCRIPT"));
            assertEquals("5", await(commands.get(key)));

            assertThrows(ExecutionException.class, () -> await(commands.eval("return redis.error_reply('ERR test')",
                new String[] {key})));
            // Runtime errors do not roll back earlier script writes, and must not trigger a replay.
            assertThrows(ExecutionException.class, () -> await(commands.eval(
                "redis.call('INCR', KEYS[1]); return redis.call('NO_SUCH_BOBA_COMMAND')", new String[] {key})));
            assertEquals("6", await(commands.get(key)));
            // Wrong output is a client decoding error, not an instruction to re-execute.
            assertThrows(ExecutionException.class, () -> await(commands.eval(INCREMENT,
                ScriptOutput.string(), new String[] {key}, "1")));
            assertEquals("7", await(commands.get(key)));
        } finally {
            await(commands.del(key));
        }
    }

    private static void verifySyncAndBinary(BobaStrawClient client) throws Exception {
        String key = "boba-lua-sync:" + UUID.randomUUID();
        byte[] prefix = ("boba-lua-binary:" + UUID.randomUUID()).getBytes(StandardCharsets.US_ASCII);
        byte[] binaryKey = Arrays.copyOf(prefix, prefix.length + 2);
        binaryKey[prefix.length] = (byte) 0xff;
        byte[] payload = {(byte) 0xfe, 0, (byte) 0xff};
        try {
            String sha = client.sync().scriptLoad(INCREMENT);
            assertEquals(Long.valueOf(1), client.sync().evalSha(sha, ScriptOutput.integer(), new String[] {key}, "1"));
            assertEquals(2, client.sync().eval(INCREMENT, new String[] {key}, "1").asLong());
            assertEquals(3, client.sync().evalSha(sha, new String[] {key}, "1").asLong());
            assertEquals(Long.valueOf(4), client.sync().eval(INCREMENT, ScriptOutput.integer(), new String[] {key}, "1"));

            byte[] source = "redis.call('SET', KEYS[1], ARGV[1]); return {redis.call('GET', KEYS[1]), ARGV[2]}"
                .getBytes(StandardCharsets.US_ASCII);
            String binarySha = await(client.binary().scriptLoad(source));
            assertEquals(digest(source), binarySha);
            assertNull(await(client.binary().get(binaryKey)));
            List<byte[]> values = await(client.binary().evalSha(binarySha,
                ScriptOutput.list(ScriptOutput.bytes()), new byte[][] {binaryKey}, payload, new byte[0]));
            assertArrayEquals(payload, values.get(0));
            assertArrayEquals(new byte[0], values.get(1));
            assertArrayEquals(payload, await(client.binary().eval("return ARGV[1]".getBytes(StandardCharsets.US_ASCII),
                ScriptOutput.bytes(), new byte[0][], payload)));
            assertTrue(await(client.binary().eval(new byte[0], new byte[0][])) instanceof RespValue.Null);
        } finally {
            client.sync().del(key);
            await(client.binary().del(binaryKey));
        }
    }

    private static String digest(byte[] source) throws Exception {
        StringBuilder result = new StringBuilder();
        for (byte item : MessageDigest.getInstance("SHA-1").digest(source)) {
            result.append(Character.forDigit((item & 255) >>> 4, 16));
            result.append(Character.forDigit(item & 15, 16));
        }
        return result.toString();
    }

    private static <T> T await(CompletionStage<T> stage) throws Exception {
        return stage.toCompletableFuture().get(5, TimeUnit.SECONDS);
    }
}
