package io.github.susongyan.bobastraw;

/** The connection failed before this command wrote any bytes to Redis. */
public final class BobaStrawCommandNotSentException extends BobaStrawConnectionException {
    public BobaStrawCommandNotSentException(String message, Throwable cause) {
        super(message, cause);
    }
}
