package io.github.susongyan.bobastraw;

import io.github.susongyan.bobastraw.protocol.RespValue;
import org.junit.jupiter.api.Test;
import java.net.ServerSocket;
import java.net.Socket;
import java.io.DataInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class CommandModelTest {
    @Test
    void everyRegisteredCommandHasConsistentRoutingAndConnectionPolicy() {
        assertFalse(CommandRegistry.all().isEmpty());
        for (CommandSpec spec : CommandRegistry.all()) {
            assertSame(spec, CommandRegistry.lookup(spec.name.toLowerCase(java.util.Locale.ROOT)));
            if (spec.connection == CommandSpec.Connection.ORDINARY) {
                assertNotEquals(CommandSpec.Keys.UNKNOWN, spec.keys, spec.name);
                assertDoesNotThrow(() -> CommandRegistry.requireOrdinary(spec.name, CommandArgs.text()));
            } else {
                assertThrows(IllegalArgumentException.class,
                    () -> CommandRegistry.requireOrdinary(spec.name, CommandArgs.text()));
                assertThrows(IllegalArgumentException.class,
                    () -> CommandRegistry.requireTransaction(spec.name, CommandArgs.text()));
            }
        }
        assertThrows(UnsupportedOperationException.class, () -> CommandRegistry.all().clear());
    }

    @Test
    void metadataExtractsAllKeysWithoutInterpretingValues() {
        Integer slot = ClusterSlot.of("{x}:a");
        assertEquals(slot, CommandRegistry.lookup("mset").slot(CommandArgs.text("{x}:a", "{y}", "{x}:b", "v")));
        assertEquals(slot, CommandRegistry.lookup("EVAL").slot(CommandArgs.text("script", "2", "{x}:a", "{x}:b", "{y}")));
        assertNull(CommandRegistry.lookup("EVAL").slot(CommandArgs.text("script", "0", "{y}")));
        assertNull(CommandRegistry.lookup("PING").slot(CommandArgs.text()));
        assertThrows(IllegalArgumentException.class, () -> CommandRegistry.lookup("MGET")
            .slot(CommandArgs.text("{x}:a", "{y}:b")));
        assertThrows(IllegalArgumentException.class, () -> CommandRegistry.lookup("EVAL")
            .slot(CommandArgs.text("script", "3", "key")));
        assertThrows(IllegalArgumentException.class, () -> CommandRegistry.lookup("MSET")
            .slot(CommandArgs.text("key")));
        assertThrows(IllegalArgumentException.class, () -> ClusterCommandRouting.explicitSlot(
            new String[] {"{x}"}, "MSET", new String[] {"{x}", "v", "{y}", "v"}));
    }

    @Test
    void binaryKeyRoutingUsesBytesIncludingHashTags() {
        byte[] key = {(byte) 0xff, '{', 0, (byte) 0xfe, '}', 1};
        byte[] other = {1, '{', 0, (byte) 0xfe, '}', (byte) 0xff};
        assertEquals(ClusterSlot.ofBytes(key), ClusterSlot.ofBytes(other));
        assertEquals(Integer.valueOf(ClusterSlot.ofBytes(key)), CommandRegistry.lookup("MSET")
            .slot(CommandArgs.binary(key, new byte[] {(byte) 0xfa}, other, new byte[0])));
        for (String value : new String[] {"123456789", "{tag}:a", "{}a{b}", "abc{a", "{{x}}", ""}) {
            assertEquals(ClusterSlot.of(value), ClusterSlot.ofBytes(ascii(value)));
        }
        assertEquals(12739, ClusterSlot.ofBytes(ascii("123456789")));
    }

    @Test
    void unknownRawCommandsNeedExplicitClusterKeysAndNeverGainRetryPermission() {
        assertNull(CommandRegistry.lookup("module.newcommand"));
        assertDoesNotThrow(() -> CommandRegistry.requireOrdinary("module.newcommand", CommandArgs.text("key")));
        assertThrows(IllegalArgumentException.class, () -> ClusterCommandRouting.slot("module.newcommand", new String[] {"key"}));
        assertEquals(Integer.valueOf(ClusterSlot.of("key")), ClusterCommandRouting.explicitSlot(
            new String[] {"key"}, "module.newcommand", new String[] {"key"}));
        assertEquals(CommandSpec.Access.READ_ONLY, CommandRegistry.lookup("GET").access);
        assertEquals(CommandSpec.Access.WRITE, CommandRegistry.lookup("INCR").access);
        assertEquals("2.6.0", CommandRegistry.lookup("BITCOUNT").since);
        assertThrows(IllegalArgumentException.class, () -> CommandRegistry.lookup("GET key"));
        assertThrows(IllegalArgumentException.class, () -> CommandArgs.commandName(new byte[] {(byte) 0xff}));
    }

    @Test
    void rawBinaryPipelineAndTransactionCannotBypassConnectionPolicies() throws Exception {
        try (ServerSocket server = new ServerSocket(0);
             BobaStrawClient client = BobaStrawClient.builder().endpoint("127.0.0.1", server.getLocalPort())
                 .protocol(ProtocolVersion.RESP2).build()) {
            for (String command : new String[] {"MULTI", "WATCH", "SUBSCRIBE", "CLIENT", "SELECT", "BLPOP",
                "WAIT", "XREAD", "XREADGROUP", "MONITOR", "RESET"}) {
                assertThrows(IllegalArgumentException.class, () -> client.executeAsync(command));
                assertThrows(IllegalArgumentException.class, () -> client.executeBinaryAsync(ascii(command)));
                assertThrows(IllegalArgumentException.class, () -> client.executeTransport(command));
                assertThrows(IllegalArgumentException.class, () -> client.pipeline().command(command));
                assertThrows(IllegalArgumentException.class, () -> new BobaStrawTransaction(null, null).command(command));
                assertThrows(IllegalArgumentException.class, () -> ClusterCommandRouting.validate(command, new String[0]));
            }
            assertThrows(IllegalArgumentException.class, () -> client.executeBatch(Arrays.asList(
                new String[] {"SET", "never-sent", "value"}, new String[] {"MULTI"})));
            assertThrows(IllegalArgumentException.class, () -> client.executeAsync("SCRIPT", "DEBUG", "YES"));
            assertThrows(IllegalArgumentException.class,
                () -> client.executeBinaryAsync(ascii("SCRIPT"), ascii("DEBUG"), ascii("YES")));
            assertThrows(IllegalArgumentException.class, () -> client.executeTransport("SCRIPT", "DEBUG", "YES"));
            assertThrows(IllegalArgumentException.class, () -> client.pipeline().command("SCRIPT", "DEBUG", "YES"));
            assertThrows(IllegalArgumentException.class,
                () -> new BobaStrawTransaction(null, null).command("SCRIPT", "DEBUG", "YES"));
            CompletionStage<RespValue> ping = client.executeAsync("PING");
            server.setSoTimeout(3000);
            try (Socket socket = server.accept()) {
                socket.setSoTimeout(3000);
                byte[] expected = ascii("*1\r\n$4\r\nPING\r\n");
                byte[] actual = new byte[expected.length];
                new DataInputStream(socket.getInputStream()).readFully(actual);
                assertArrayEquals(expected, actual, "No rejected command or partial batch may reach the socket");
                socket.getOutputStream().write(ascii("+PONG\r\n"));
                socket.getOutputStream().flush();
                assertEquals("PONG", ping.toCompletableFuture().get(3, TimeUnit.SECONDS).asString());
            }
        }
    }

    @Test
    void decodersPreserveResp2Resp3BinaryHashSetAndNullableNumbers() {
        RespValue field = new RespValue.BlobString(new byte[] {(byte) 0xff});
        RespValue value = new RespValue.BlobString(new byte[0]);
        Map<RespValue, RespValue> map = new LinkedHashMap<RespValue, RespValue>();
        map.put(field, value);
        for (RespValue response : new RespValue[] {new RespValue.Array(Arrays.asList(field, value)),
            new RespValue.MapValue(map)}) {
            List<Map.Entry<byte[], byte[]>> entries = CommandDecoders.BYTE_ENTRIES.apply(response);
            assertEquals(1, entries.size());
            assertArrayEquals(new byte[] {(byte) 0xff}, entries.get(0).getKey());
            assertArrayEquals(new byte[0], entries.get(0).getValue());
        }
        assertArrayEquals(new byte[] {(byte) 0xff}, CommandDecoders.BYTE_LIST
            .apply(new RespValue.SetValue(Collections.singletonList(field))).get(0));
        assertNull(CommandDecoders.NULLABLE_LONG.apply(RespValue.Null.INSTANCE));
        assertNull(CommandDecoders.NULLABLE_DOUBLE.apply(RespValue.Null.INSTANCE));
        assertEquals(Double.valueOf(1.25), CommandDecoders.NULLABLE_DOUBLE.apply(new RespValue.DoubleValue(1.25)));
        assertEquals(Double.valueOf(1.25), CommandDecoders.NULLABLE_DOUBLE.apply(new RespValue.BlobString(ascii("1.25"))));
        assertThrows(IllegalStateException.class, () -> CommandDecoders.BYTE_ENTRIES
            .apply(new RespValue.Array(Collections.singletonList(field))));
    }

    private static byte[] ascii(String value) {
        return value.getBytes(StandardCharsets.US_ASCII);
    }
}
