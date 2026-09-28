package io.github.susongyan.bobastraw;

import io.github.susongyan.bobastraw.protocol.RespValue;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import static org.junit.jupiter.api.Assertions.*;

class TypedBatchResultTest {
    @Test
    void handlesAreOwnerBoundAndErrorsDoNotHideOtherPositions() {
        Object owner = new Object();
        BobaStrawBatchResult result = new BobaStrawBatchResult(owner, Arrays.asList(
            new RespValue.Number(3), new RespValue.Error("ERR one"),
            new RespValue.BlobError("ERR two".getBytes(StandardCharsets.UTF_8)), RespValue.Null.INSTANCE), 4, false);
        assertEquals(Long.valueOf(3), result.get(new BobaStrawCommandHandle<Long>(owner, 0, CommandDecoders.LONG)));
        assertThrows(BobaStrawServerException.class,
            () -> result.get(new BobaStrawCommandHandle<String>(owner, 1, CommandDecoders.STRING)));
        assertThrows(BobaStrawServerException.class,
            () -> result.get(new BobaStrawCommandHandle<String>(owner, 2, CommandDecoders.STRING)));
        assertNull(result.get(new BobaStrawCommandHandle<String>(owner, 3, CommandDecoders.STRING)));
        assertThrows(IllegalArgumentException.class,
            () -> result.get(new BobaStrawCommandHandle<Long>(new Object(), 0, CommandDecoders.LONG)));
        assertThrows(UnsupportedOperationException.class, () -> result.replies().clear());
    }

    @Test
    void abortIsNotEmptySuccessAndReplyCountMustMatch() {
        Object owner = new Object();
        BobaStrawBatchResult aborted = new BobaStrawBatchResult(owner, Collections.emptyList(), 1, true);
        assertTrue(aborted.isAborted());
        assertThrows(IllegalStateException.class,
            () -> aborted.get(new BobaStrawCommandHandle<String>(owner, 0, CommandDecoders.STRING)));
        assertFalse(new BobaStrawBatchResult(owner, Collections.emptyList(), 0, false).isAborted());
        assertThrows(BobaStrawProtocolException.class,
            () -> new BobaStrawBatchResult(owner, Collections.emptyList(), 1, false));
    }
}
