package io.github.susongyan.bobastraw;

/**
 * Boba Straw rejected work locally because a bounded client queue has no remaining capacity.
 *
 * <p>No Redis command bytes are sent for a command rejected with this exception.</p>
 */
public final class BobaStrawBackpressureException extends BobaStrawConnectionException {
    public BobaStrawBackpressureException(String message) {
        super(message);
    }
}
