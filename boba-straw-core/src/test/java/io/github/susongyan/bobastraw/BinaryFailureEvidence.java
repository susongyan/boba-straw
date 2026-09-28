package io.github.susongyan.bobastraw;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;

/** Bounded synthetic-fixture diagnostics only. Never installed in production clients. */
final class BinaryFailureEvidence {
    private final Object connection;
    private final ByteArrayOutputStream attempted = new ByteArrayOutputStream();
    private int completedBytes;

    BinaryFailureEvidence(BobaStrawClient client) throws Exception {
        // Retain this physical connection, not a replacement created by background reconnect.
        connection = field(client, "connection");
    }

    synchronized void write(OutputStream output, byte[] bytes) throws IOException {
        if (attempted.size() + bytes.length > 256) {
            throw new IllegalArgumentException("Synthetic evidence exceeds 256-byte limit");
        }
        attempted.write(bytes, 0, bytes.length);
        output.write(bytes);
        completedBytes += bytes.length;
    }

    synchronized String snapshot() {
        String sent = "serverAttempted=" + hex(attempted.toByteArray(), 0, attempted.size())
            + ", serverWriteCompletedBytes=" + completedBytes;
        try {
            Object decoder = field(connection, "decoder");
            synchronized (decoder) {
                if (field(decoder, "failure") == null) {
                    return sent + ", decoderNotTerminal=true";
                }
                byte[] input = (byte[]) field(decoder, "input");
                int read = (Integer) field(decoder, "readIndex");
                int write = (Integer) field(decoder, "writeIndex");
                int start = Math.max(0, read - 16);
                int end = Math.min(write, start + 64);
                // A protocol failure unwinds read() before another socket read can reuse this buffer.
                ByteBuffer lastRead = (ByteBuffer) field(connection, "readBuffer");
                return sent + ", decoderRead=" + read + ", decoderWrite=" + write
                    + ", decoderWindowOffset=" + start + ", decoderWindow=" + hex(input, start, end)
                    + ", lastSocketChunkBytes=" + lastRead.position()
                    + ", lastSocketChunkPrefix=" + hex(lastRead.array(), 0, Math.min(64, lastRead.position()));
            }
        } catch (Exception error) {
            return sent + ", snapshotUnavailable=" + error.getClass().getSimpleName();
        }
    }

    private static Object field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static String hex(byte[] bytes, int start, int end) {
        StringBuilder value = new StringBuilder();
        for (int i = start; i < end; i++) {
            int unsigned = bytes[i] & 255;
            value.append(Character.forDigit(unsigned >>> 4, 16));
            value.append(Character.forDigit(unsigned & 15, 16));
        }
        return value.toString();
    }
}
