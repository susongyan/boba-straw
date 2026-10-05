package io.github.susongyan.bobastraw;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ScriptBatchTest {
    @Test
    void registeredBatchCapturesArgumentsWithoutExecutingOrRecovering() {
        BobaStrawScripts scripts = new BobaStrawScripts(keys -> {
            throw new AssertionError("Batch construction must not select a connection");
        }, Duration.ofSeconds(1), true);
        List<TypedCommand<?>> queued = new ArrayList<TypedCommand<?>>();
        Object owner = new Object();
        BobaStrawBatchCommands batch = new BobaStrawBatchCommands(new BobaStrawBatchCommands.Enqueuer() {
            public <T> BobaStrawCommandHandle<T> enqueue(TypedCommand<T> command) {
                queued.add(command);
                return new BobaStrawCommandHandle<T>(owner, queued.size() - 1, command.decoder());
            }
        }, scripts);
        try {
            scripts.register("echo", "return ARGV[1]", ScriptOutput.string());
            String[] keys = {"key"};
            String[] args = {"茶"};
            batch.script("echo", ScriptOutput.string(), keys, args);
            keys[0] = "changed";
            args[0] = "changed";
            batch.eval("return 1", ScriptOutput.integer(), new String[0]);
            batch.evalSha("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
                ScriptOutput.integer(), new String[0]);
            batch.scriptLoad("return 1");
            assertArrayEquals(new String[] {"return ARGV[1]", "1", "key", "茶"},
                queued.get(0).arguments());
            assertEquals("EVAL", queued.get(0).name());
            assertEquals("EVAL", queued.get(1).name());
            assertEquals("EVALSHA", queued.get(2).name());
            assertEquals("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", queued.get(2).arguments()[0]);
            assertArrayEquals(new String[] {"LOAD", "return 1"}, queued.get(3).arguments());
            assertThrows(IllegalArgumentException.class,
                () -> batch.script("missing", ScriptOutput.string(), new String[0]));
            assertThrows(IllegalArgumentException.class,
                () -> batch.script("echo", ScriptOutput.integer(), new String[0]));
            scripts.register("binary", new byte[] {(byte) 0xff}, ScriptOutput.bytes());
            assertThrows(IllegalArgumentException.class,
                () -> batch.script("binary", ScriptOutput.bytes(), new String[0]));
            assertEquals(4, queued.size());
            scripts.close();
            assertEquals("return ARGV[1]", queued.get(0).arguments()[0]);
            assertThrows(BobaStrawConnectionException.class,
                () -> batch.script("echo", ScriptOutput.string(), new String[0]));
        } finally {
            scripts.close();
        }
    }
}
