package io.github.susongyan.bobastraw;
public class BobaStrawConnectionException extends RuntimeException {
    public BobaStrawConnectionException(String message, Throwable cause) {
        super(message, cause);
    }

    public BobaStrawConnectionException(String message) {
        super(message);
    }
}
