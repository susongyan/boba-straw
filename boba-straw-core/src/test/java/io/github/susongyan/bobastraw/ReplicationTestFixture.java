package io.github.susongyan.bobastraw;

import io.github.susongyan.bobastraw.internal.NioConnection;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Fixture-only replication fence. SET and WAIT must use the SAME dedicated socket. */
final class ReplicationTestFixture {
    private ReplicationTestFixture() {
    }

    static void writeAndAwaitReplica(BobaStrawClient owner, String key, String value, String timeout) throws Exception {
        NioConnection dedicated = owner.openPubSubConnection(null);
        try {
            assertEquals("OK", dedicated.execute(new String[] {"SET", key, value})
                .toCompletableFuture().get(8, TimeUnit.SECONDS).asString());
            assertEquals(1, dedicated.execute(new String[] {"WAIT", "1", timeout})
                .toCompletableFuture().get(8, TimeUnit.SECONDS).asLong());
        } finally {
            owner.closeDedicated(dedicated);
        }
    }
}
