package io.github.susongyan.bobastraw.internal;

import io.github.susongyan.bobastraw.protocol.RespCodec;
import java.nio.ByteBuffer;

/** Internal immutable wire snapshot. This type does not authorize a command or implement retries. */
public final class EncodedCommand {
    private final byte[] frame;

    public EncodedCommand(byte[][] command) {
        frame = RespCodec.encodeCommand(command);
    }

    /** Each request owns its position/limit; neither content nor backing array can be modified. */
    public ByteBuffer buffer() {
        return ByteBuffer.wrap(frame).asReadOnlyBuffer();
    }
}
