package io.github.susongyan.bobastraw;

import io.github.susongyan.bobastraw.protocol.RespValue;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Ordered batch replies. A successful batch may contain individual server errors. */
public final class BobaStrawBatchResult {
    private final Object owner;
    private final List<RespValue> replies;
    private final boolean aborted;

    BobaStrawBatchResult(Object owner, List<RespValue> replies, int expected, boolean aborted) {
        if ((!aborted && replies.size() != expected) || (aborted && !replies.isEmpty())) {
            throw new BobaStrawProtocolException("Batch reply count does not match queued commands");
        }
        this.owner = owner;
        this.replies = Collections.unmodifiableList(new ArrayList<RespValue>(replies));
        this.aborted = aborted;
    }

    /** True only for EXEC returning null after a WATCH conflict; not an execution failure. */
    public boolean isAborted() {
        return aborted;
    }

    /** Includes positions queued using the Raw command API. Does not deep-copy RESP payloads. */
    public List<RespValue> replies() {
        return replies;
    }

    /**
     * Decodes on the caller's thread. A server error throws BobaStrawServerException;
     * other successful positions remain accessible. Handles from another batch are rejected.
     */
    public <T> T get(BobaStrawCommandHandle<T> handle) {
        if (handle == null || handle.owner != owner) {
            throw new IllegalArgumentException("Handle belongs to another batch");
        }
        if (aborted) {
            throw new IllegalStateException("Transaction was aborted by WATCH");
        }
        RespValue value = replies.get(handle.index);
        if (value instanceof RespValue.Error) {
            throw new BobaStrawServerException(((RespValue.Error) value).message);
        }
        if (value instanceof RespValue.BlobError) {
            throw new BobaStrawServerException(((RespValue.BlobError) value).message());
        }
        return handle.decoder.apply(value);
    }
}
