package io.github.susongyan.bobastraw;

/** The connection failed after this command wrote at least one byte to Redis. */
public final class BobaStrawCommandMayHaveExecutedException extends BobaStrawConnectionException {
    public BobaStrawCommandMayHaveExecutedException(String message, Throwable cause) {
        super(message, cause);
    }
}
