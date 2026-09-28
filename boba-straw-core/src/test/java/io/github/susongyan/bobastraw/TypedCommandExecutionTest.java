package io.github.susongyan.bobastraw;

import io.github.susongyan.bobastraw.protocol.RespValue;
import io.github.susongyan.bobastraw.protocol.RespCodec;
import io.github.susongyan.bobastraw.internal.EncodedCommand;
import java.nio.ByteBuffer;
import java.nio.ReadOnlyBufferException;
import org.junit.jupiter.api.Test;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class TypedCommandExecutionTest {
    private static RespValue.Array decode(EncodedCommand command) {
        ByteBuffer buffer = command.buffer();
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        RespCodec.Decoder decoder = new RespCodec.Decoder();
        decoder.feed(bytes, bytes.length);
        RespValue.Array result = (RespValue.Array) decoder.poll();
        assertNotNull(result);
        assertNull(decoder.poll());
        return result;
    }

    @Test
    void binaryInvocationOwnsBothArrayLevelsAndRejectsWrongExecutor() {
        byte[][] arguments = {new byte[] {(byte) 0xff, 0}, new byte[0]};
        TypedCommand<byte[]> command = TypedCommand.binary("set", CommandDecoders.BYTES, arguments);
        arguments[0][0] = 1;
        arguments[1] = new byte[] {2};
        ByteBuffer exposed = command.binaryFrame().buffer();
        assertTrue(exposed.isReadOnly());
        assertThrows(ReadOnlyBufferException.class, () -> exposed.put(0, (byte) 3));
        assertThrows(ReadOnlyBufferException.class, exposed::array);
        exposed.position(exposed.limit());
        assertEquals(0, command.binaryFrame().buffer().position());
        RespValue.Array encoded = decode(command.binaryFrame());
        assertEquals("SET", command.name());
        assertEquals("SET", encoded.values.get(0).asString());
        assertArrayEquals(new byte[] {(byte) 0xff, 0}, CommandDecoders.BYTES.apply(encoded.values.get(1)));
        assertArrayEquals(new byte[0], CommandDecoders.BYTES.apply(encoded.values.get(2)));
        CommandExecutor text = (name, args) -> {
            fail("Binary command must not reach a text executor");
            return null;
        };
        BinaryCommandExecutor binary = frame -> {
            fail("Text command must not reach a binary executor");
            return null;
        };
        assertThrows(IllegalStateException.class, () -> text.execute(command));
        assertThrows(IllegalStateException.class,
            () -> binary.execute(new TypedCommand<String>("GET", CommandDecoders.STRING, "key")));
        assertThrows(IllegalArgumentException.class,
            () -> TypedCommand.binary("MULTI", CommandDecoders.BYTES));
        assertThrows(IllegalArgumentException.class,
            () -> TypedCommand.binary("GET", CommandDecoders.BYTES, (byte[]) null));
        assertThrows(IllegalArgumentException.class,
            () -> TypedCommand.binary("GET", CommandDecoders.BYTES, (byte[][]) null));
        assertThrows(IllegalArgumentException.class,
            () -> TypedCommand.binary("GET", null, new byte[0]));
    }

    @Test
    void binaryFacadePreservesBytesCancellationAndFailureWithoutReplay() throws Exception {
        AtomicInteger submissions = new AtomicInteger();
        CompletableFuture<RespValue> source = new CompletableFuture<RespValue>();
        BobaStrawBinaryCommands commands = BobaStrawBinaryCommands.withExecutor(frame -> {
            submissions.incrementAndGet();
            RespValue.Array encoded = decode(frame);
            assertEquals("GET", encoded.values.get(0).asString());
            assertArrayEquals(new byte[] {(byte) 0xff, 0}, CommandDecoders.BYTES.apply(encoded.values.get(1)));
            return source;
        });
        assertTrue(commands.get(new byte[] {(byte) 0xff, 0}).toCompletableFuture().cancel(false));
        assertTrue(source.isCancelled());
        assertEquals(1, submissions.get());

        CompletableFuture<RespValue> failed = new CompletableFuture<RespValue>();
        RuntimeException failure = new IllegalStateException("possibly executed");
        BobaStrawBinaryCommands failing = BobaStrawBinaryCommands.withExecutor(frame -> {
            submissions.incrementAndGet();
            return failed;
        });
        CompletionStage<Long> result = failing.incr(new byte[0]);
        failed.completeExceptionally(failure);
        assertSame(failure, assertThrows(ExecutionException.class,
            () -> result.toCompletableFuture().get()).getCause());
        assertEquals(2, submissions.get());
    }

    @Test
    void binaryValidationPrecedesSubmissionAndMalformedReplyFailsMapping() {
        AtomicInteger submissions = new AtomicInteger();
        BobaStrawBinaryCommands commands = BobaStrawBinaryCommands.withExecutor(frame -> {
            submissions.incrementAndGet();
            return CompletableFuture.completedFuture(new RespValue.SimpleString("not-a-number"));
        });
        assertThrows(IllegalArgumentException.class, () -> commands.get(null));
        assertThrows(IllegalArgumentException.class, () -> commands.set(new byte[0], null));
        assertThrows(IllegalArgumentException.class, () -> commands.del());
        assertThrows(IllegalArgumentException.class, () -> commands.mget());
        assertEquals(0, submissions.get());
        assertThrows(ExecutionException.class, () -> commands.hlen(new byte[0]).toCompletableFuture().get());
        assertEquals(1, submissions.get());
    }

    @Test
    void invocationOwnsArgumentsAndRejectsStateCommands() {
        String[] arguments = {"key"};
        TypedCommand<String> command = new TypedCommand<String>("get", CommandDecoders.STRING, arguments);
        arguments[0] = "changed";
        command.arguments()[0] = "also changed";
        assertEquals("GET", command.name());
        assertArrayEquals(new String[] {"key"}, command.arguments());
        assertThrows(IllegalArgumentException.class,
            () -> new TypedCommand<String>("MULTI", CommandDecoders.STRING));
        assertThrows(IllegalArgumentException.class,
            () -> new TypedCommand<String>("GET", CommandDecoders.STRING, (String) null));
        assertThrows(IllegalArgumentException.class,
            () -> new TypedCommand<String>("GET", null, "key"));
    }

    @Test
    void facadePreservesCancellationAndFailureWithoutReplay() throws Exception {
        AtomicInteger submissions = new AtomicInteger();
        CompletableFuture<RespValue> source = new CompletableFuture<RespValue>();
        BobaStrawAsyncCommands commands = new BobaStrawAsyncCommands((name, args) -> {
            submissions.incrementAndGet();
            assertEquals("GET", name);
            assertArrayEquals(new String[] {"key"}, args);
            return source;
        });
        assertTrue(commands.get("key").toCompletableFuture().cancel(false));
        assertTrue(source.isCancelled());
        assertEquals(1, submissions.get());

        CompletableFuture<RespValue> failed = new CompletableFuture<RespValue>();
        RuntimeException failure = new IllegalStateException("possibly executed");
        BobaStrawAsyncCommands failing = new BobaStrawAsyncCommands((name, args) -> {
            submissions.incrementAndGet();
            return failed;
        });
        CompletionStage<Long> result = failing.incr("key");
        failed.completeExceptionally(failure);
        assertSame(failure, assertThrows(ExecutionException.class,
            () -> result.toCompletableFuture().get()).getCause());
        assertEquals(2, submissions.get());
    }

    @Test
    void decoderFailureTerminatesResultAndSpecialCommandsNeverReachOrdinaryExecutor() {
        AtomicInteger submissions = new AtomicInteger();
        BobaStrawAsyncCommands commands = new BobaStrawAsyncCommands((name, args) -> {
            submissions.incrementAndGet();
            return CompletableFuture.completedFuture(new RespValue.SimpleString("not-a-number"));
        });
        assertThrows(ExecutionException.class, () -> commands.hlen("key").toCompletableFuture().get());
        assertThrows(UnsupportedOperationException.class, () -> commands.blpop(1, "key"));
        assertThrows(UnsupportedOperationException.class, () -> commands.brpop(1, "key"));
        assertEquals(1, submissions.get());
    }
}
