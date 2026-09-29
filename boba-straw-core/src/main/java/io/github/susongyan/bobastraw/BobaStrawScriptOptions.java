package io.github.susongyan.bobastraw;

/**
 * Immutable per-client limits for the local script registry.
 * These limits do not configure Redis or replace connection admission limits.
 */
public final class BobaStrawScriptOptions {
    private static final BobaStrawScriptOptions DEFAULT = new Builder().build();

    private final int maxRegisteredScripts;
    private final long maxScriptBytes;
    private final int maxCacheHints;
    private final int maxInFlightExecutions;

    private BobaStrawScriptOptions(Builder builder) {
        this.maxRegisteredScripts = builder.maxRegisteredScripts;
        this.maxScriptBytes = builder.maxScriptBytes;
        this.maxCacheHints = builder.maxCacheHints;
        this.maxInFlightExecutions = builder.maxInFlightExecutions;
    }

    public static BobaStrawScriptOptions defaults() {
        return DEFAULT;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Maximum registered definitions; default 1024. Definitions are never evicted. */
    public int maxRegisteredScripts() {
        return maxRegisteredScripts;
    }

    /** Maximum total registered body bytes; default 16 MiB, not a total heap limit. */
    public long maxScriptBytes() {
        return maxScriptBytes;
    }

    /** Maximum connection/SHA hints; default 4096. Old hints may be evicted. */
    public int maxCacheHints() {
        return maxCacheHints;
    }

    /** Maximum unfinished logical executions; default 4096. Excess executions are rejected. */
    public int maxInFlightExecutions() {
        return maxInFlightExecutions;
    }

    public static final class Builder {
        private int maxRegisteredScripts = 1024;
        private long maxScriptBytes = 16L * 1024L * 1024L;
        private int maxCacheHints = 4096;
        private int maxInFlightExecutions = 4096;

        public Builder maxRegisteredScripts(int value) {
            if (value < 1) {
                throw new IllegalArgumentException("maxRegisteredScripts must be positive");
            }
            this.maxRegisteredScripts = value;
            return this;
        }

        public Builder maxScriptBytes(long value) {
            if (value < 1) {
                throw new IllegalArgumentException("maxScriptBytes must be positive");
            }
            this.maxScriptBytes = value;
            return this;
        }

        public Builder maxCacheHints(int value) {
            if (value < 1) {
                throw new IllegalArgumentException("maxCacheHints must be positive");
            }
            this.maxCacheHints = value;
            return this;
        }

        public Builder maxInFlightExecutions(int value) {
            if (value < 1) {
                throw new IllegalArgumentException("maxInFlightExecutions must be positive");
            }
            this.maxInFlightExecutions = value;
            return this;
        }

        public BobaStrawScriptOptions build() {
            return new BobaStrawScriptOptions(this);
        }
    }
}
