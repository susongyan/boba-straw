package io.github.susongyan.bobastraw.protocol;

/**
 * Immutable limits that protect a RESP decoder from malformed or unexpectedly large replies.
 *
 * <p>The defaults are deliberately conservative for a shared application client. Applications
 * that intentionally read larger values or collections can raise the relevant bound explicitly.</p>
 */
public final class RespLimits {
    private static final int DEFAULT_MAX_BUFFERED_BYTES = 64 * 1024 * 1024;
    private static final int DEFAULT_MAX_RESPONSE_BYTES = 64 * 1024 * 1024;
    private static final int DEFAULT_MAX_BULK_LENGTH = 32 * 1024 * 1024;
    private static final int DEFAULT_MAX_LINE_LENGTH = 64 * 1024;
    private static final int DEFAULT_MAX_NESTING_DEPTH = 64;
    private static final int DEFAULT_MAX_AGGREGATE_ELEMENTS = 100_000;
    private static final RespLimits DEFAULT = new Builder().build();

    private final int maxBufferedBytes;
    private final int maxResponseBytes;
    private final int maxBulkLength;
    private final int maxLineLength;
    private final int maxNestingDepth;
    private final int maxAggregateElements;

    private RespLimits(Builder builder) {
        this.maxBufferedBytes = builder.maxBufferedBytes;
        this.maxResponseBytes = builder.maxResponseBytes;
        this.maxBulkLength = builder.maxBulkLength;
        this.maxLineLength = builder.maxLineLength;
        this.maxNestingDepth = builder.maxNestingDepth;
        this.maxAggregateElements = builder.maxAggregateElements;
    }

    /** Returns the default limits used by clients and by {@link RespCodec.Decoder#Decoder()}. */
    public static RespLimits defaults() {
        return DEFAULT;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Maximum undecoded wire bytes buffered by one decoder. */
    public int maxBufferedBytes() {
        return maxBufferedBytes;
    }

    /** Maximum wire bytes in one top-level RESP value, including markers and CRLF. */
    public int maxResponseBytes() {
        return maxResponseBytes;
    }

    /** Maximum payload bytes declared by a RESP bulk, blob error, or verbatim string. */
    public int maxBulkLength() {
        return maxBulkLength;
    }

    /** Maximum bytes in one RESP line excluding its marker and CRLF. */
    public int maxLineLength() {
        return maxLineLength;
    }

    /** Maximum aggregate nesting depth in one top-level RESP value. */
    public int maxNestingDepth() {
        return maxNestingDepth;
    }

    /** Maximum cumulative aggregate child values in one top-level RESP value. */
    public int maxAggregateElements() {
        return maxAggregateElements;
    }

    public static final class Builder {
        private int maxBufferedBytes = DEFAULT_MAX_BUFFERED_BYTES;
        private int maxResponseBytes = DEFAULT_MAX_RESPONSE_BYTES;
        private int maxBulkLength = DEFAULT_MAX_BULK_LENGTH;
        private int maxLineLength = DEFAULT_MAX_LINE_LENGTH;
        private int maxNestingDepth = DEFAULT_MAX_NESTING_DEPTH;
        private int maxAggregateElements = DEFAULT_MAX_AGGREGATE_ELEMENTS;

        public Builder maxBufferedBytes(int value) {
            this.maxBufferedBytes = positive("maxBufferedBytes", value);
            return this;
        }

        public Builder maxResponseBytes(int value) {
            this.maxResponseBytes = positive("maxResponseBytes", value);
            return this;
        }

        public Builder maxBulkLength(int value) {
            this.maxBulkLength = positive("maxBulkLength", value);
            return this;
        }

        public Builder maxLineLength(int value) {
            this.maxLineLength = positive("maxLineLength", value);
            return this;
        }

        public Builder maxNestingDepth(int value) {
            this.maxNestingDepth = positive("maxNestingDepth", value);
            return this;
        }

        public Builder maxAggregateElements(int value) {
            this.maxAggregateElements = positive("maxAggregateElements", value);
            return this;
        }

        public RespLimits build() {
            if (maxBulkLength > maxResponseBytes) {
                throw new IllegalArgumentException(
                    "maxBulkLength must not exceed maxResponseBytes"
                );
            }
            if (maxLineLength > maxResponseBytes) {
                throw new IllegalArgumentException(
                    "maxLineLength must not exceed maxResponseBytes"
                );
            }
            if ((long) maxBufferedBytes < (long) maxLineLength + 3L) {
                throw new IllegalArgumentException(
                    "maxBufferedBytes must be at least maxLineLength plus marker and CRLF"
                );
            }
            return new RespLimits(this);
        }

        private static int positive(String name, int value) {
            if (value < 1) {
                throw new IllegalArgumentException(name + " must be positive");
            }
            return value;
        }
    }
}
