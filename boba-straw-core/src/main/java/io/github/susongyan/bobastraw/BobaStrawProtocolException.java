package io.github.susongyan.bobastraw;

/**
 * Redis sent malformed RESP data or a response exceeded the configured protocol limits.
 *
 * <p>The physical connection is no longer safe to use after this error. Boba Straw closes it
 * and does not retry any command that may already have reached Redis.</p>
 */
public final class BobaStrawProtocolException extends BobaStrawConnectionException {
    public BobaStrawProtocolException(String message) {
        super(message);
    }

    public BobaStrawProtocolException(String message, Throwable cause) {
        super(message, cause);
    }
}
