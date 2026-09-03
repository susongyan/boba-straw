package io.github.susongyan.bobastraw;

/**
 * A command deadline elapsed before its reply was received.
 *
 * <p>Use {@link #mayHaveExecuted()} to distinguish a request that was still local from one
 * whose bytes may already have reached Redis.</p>
 */
public final class BobaStrawCommandTimeoutException extends BobaStrawConnectionException {
    private final boolean mayHaveExecuted;

    public BobaStrawCommandTimeoutException(String message, Throwable cause) {
        this(message, cause, true);
    }

    public BobaStrawCommandTimeoutException(
        String message,
        Throwable cause,
        boolean mayHaveExecuted
    ) {
        super(message, cause);
        this.mayHaveExecuted = mayHaveExecuted;
    }

    /** Returns whether Redis may already have received and executed this command. */
    public boolean mayHaveExecuted() {
        return mayHaveExecuted;
    }
}
