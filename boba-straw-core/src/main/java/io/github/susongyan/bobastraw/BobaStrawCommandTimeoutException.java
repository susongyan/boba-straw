package io.github.susongyan.bobastraw;
/** A timeout is deliberately ambiguous: Redis may already have executed the command. */
public final class BobaStrawCommandTimeoutException extends BobaStrawConnectionException {
    public BobaStrawCommandTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}
