package io.github.susongyan.bobastraw;

/** An explicit Redis error reply, distinct from transport and result-delivery failures. */
public final class BobaStrawServerException extends BobaStrawConnectionException {
    public BobaStrawServerException(String message) {
        super(message);
    }
}
