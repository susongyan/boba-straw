package io.github.susongyan.bobastraw.internal;

/**
 * Service limits applied by an EventLoop and its connections.
 *
 * <p>The values are internal implementation controls rather than application-facing tuning
 * knobs. They bound a busy connection so another connection assigned to the same EventLoop can
 * make progress before the next slice.</p>
 */
final class NioIoLimits {
    static final NioIoLimits DEFAULT = new NioIoLimits(
        16 * 1024,
        64 * 1024,
        32,
        64 * 1024,
        64,
        256
    );

    final int readBufferSize;
    final int maxReadBytesPerTurn;
    final int maxGatheringFrames;
    final int maxWriteBytesPerTurn;
    final int maxDecodedResponsesPerTurn;
    final int maxTasksPerTurn;

    NioIoLimits(
        int readBufferSize,
        int maxReadBytesPerTurn,
        int maxGatheringFrames,
        int maxWriteBytesPerTurn,
        int maxDecodedResponsesPerTurn,
        int maxTasksPerTurn
    ) {
        if (readBufferSize < 1) {
            throw new IllegalArgumentException("readBufferSize must be positive");
        }
        if (maxReadBytesPerTurn < 1) {
            throw new IllegalArgumentException("maxReadBytesPerTurn must be positive");
        }
        if (maxGatheringFrames < 1) {
            throw new IllegalArgumentException("maxGatheringFrames must be positive");
        }
        if (maxWriteBytesPerTurn < 1) {
            throw new IllegalArgumentException("maxWriteBytesPerTurn must be positive");
        }
        if (maxDecodedResponsesPerTurn < 1) {
            throw new IllegalArgumentException("maxDecodedResponsesPerTurn must be positive");
        }
        if (maxTasksPerTurn < 1) {
            throw new IllegalArgumentException("maxTasksPerTurn must be positive");
        }
        this.readBufferSize = readBufferSize;
        this.maxReadBytesPerTurn = maxReadBytesPerTurn;
        this.maxGatheringFrames = maxGatheringFrames;
        this.maxWriteBytesPerTurn = maxWriteBytesPerTurn;
        this.maxDecodedResponsesPerTurn = maxDecodedResponsesPerTurn;
        this.maxTasksPerTurn = maxTasksPerTurn;
    }
}
