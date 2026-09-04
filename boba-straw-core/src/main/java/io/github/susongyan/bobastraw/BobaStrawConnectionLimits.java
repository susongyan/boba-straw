package io.github.susongyan.bobastraw;

/**
 * Immutable admission limits for application commands on one physical Redis connection.
 *
 * <p>The limits cover a command from the point Boba Straw accepts it until its Redis response
 * has been consumed. A cancelled or timed-out command that was already written remains counted
 * until its response is drained, preserving FIFO response matching. Handshake and internal idle
 * health-check commands do not consume this application-command budget.</p>
 */
public final class BobaStrawConnectionLimits {
    private static final int DEFAULT_MAX_IN_FLIGHT_COMMANDS = 4_096;
    private static final long DEFAULT_MAX_QUEUED_WRITE_BYTES = 16L * 1024L * 1024L;
    private static final BobaStrawConnectionLimits DEFAULT = new Builder().build();

    private final int maxInFlightCommands;
    private final long maxQueuedWriteBytes;

    private BobaStrawConnectionLimits(Builder builder) {
        this.maxInFlightCommands = builder.maxInFlightCommands;
        this.maxQueuedWriteBytes = builder.maxQueuedWriteBytes;
    }

    /** Returns the conservative limits used unless a client builder overrides them. */
    public static BobaStrawConnectionLimits defaults() {
        return DEFAULT;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Maximum application commands accepted by one physical connection at one time. */
    public int maxInFlightCommands() {
        return maxInFlightCommands;
    }

    /**
     * Maximum encoded application-command bytes awaiting socket write on one physical connection.
     *
     * <p>The budget is released as the operating system accepts bytes. The independent
     * {@link #maxInFlightCommands()} limit still reserves one response slot until Redis replies.</p>
     */
    public long maxQueuedWriteBytes() {
        return maxQueuedWriteBytes;
    }

    public static final class Builder {
        private int maxInFlightCommands = DEFAULT_MAX_IN_FLIGHT_COMMANDS;
        private long maxQueuedWriteBytes = DEFAULT_MAX_QUEUED_WRITE_BYTES;

        public Builder maxInFlightCommands(int value) {
            if (value < 1) {
                throw new IllegalArgumentException("maxInFlightCommands must be positive");
            }
            this.maxInFlightCommands = value;
            return this;
        }

        public Builder maxQueuedWriteBytes(long value) {
            if (value < 1L) {
                throw new IllegalArgumentException("maxQueuedWriteBytes must be positive");
            }
            this.maxQueuedWriteBytes = value;
            return this;
        }

        public BobaStrawConnectionLimits build() {
            return new BobaStrawConnectionLimits(this);
        }
    }
}
