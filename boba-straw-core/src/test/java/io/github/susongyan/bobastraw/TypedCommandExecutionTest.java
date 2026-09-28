package io.github.susongyan.bobastraw;

import io.github.susongyan.bobastraw.protocol.RespValue;
import org.junit.jupiter.api.Test;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class TypedCommandExecutionTest {
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
