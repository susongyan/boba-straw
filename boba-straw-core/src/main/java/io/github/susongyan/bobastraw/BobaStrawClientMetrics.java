package io.github.susongyan.bobastraw;

import java.time.Duration;

/** Immutable snapshot of a standalone client's shared-connection lifecycle and admission state. */
public final class BobaStrawClientMetrics {
    private final BobaStrawConnectionState sharedConnectionState;
    private final long connectionCreations;
    private final long reconnectAttempts;
    private final long successfulReconnects;
    private final int consecutiveReconnectFailures;
    private final Duration nextReconnectDelay;
    private final int inFlightCommands;
    private final long queuedWriteBytes;
    private final long connectionBackpressureRejections;

    BobaStrawClientMetrics(
        BobaStrawConnectionState sharedConnectionState,
        long connectionCreations,
        long reconnectAttempts,
        long successfulReconnects,
        int consecutiveReconnectFailures,
        Duration nextReconnectDelay,
        int inFlightCommands,
        long queuedWriteBytes,
        long connectionBackpressureRejections
    ) {
        this.sharedConnectionState = sharedConnectionState;
        this.connectionCreations = connectionCreations;
        this.reconnectAttempts = reconnectAttempts;
        this.successfulReconnects = successfulReconnects;
        this.consecutiveReconnectFailures = consecutiveReconnectFailures;
        this.nextReconnectDelay = nextReconnectDelay;
        this.inFlightCommands = inFlightCommands;
        this.queuedWriteBytes = queuedWriteBytes;
        this.connectionBackpressureRejections = connectionBackpressureRejections;
    }

    public BobaStrawConnectionState sharedConnectionState() {
        return sharedConnectionState;
    }

    /** Physical shared-connection objects created since this client was built. */
    public long connectionCreations() {
        return connectionCreations;
    }

    /** Background replacement attempts after the initial shared connection. */
    public long reconnectAttempts() {
        return reconnectAttempts;
    }

    /** Replacement connections that finished connect, negotiation, and authentication. */
    public long successfulReconnects() {
        return successfulReconnects;
    }

    /** Consecutive connection attempts that closed before becoming ready. */
    public int consecutiveReconnectFailures() {
        return consecutiveReconnectFailures;
    }

    /** Delay for the currently scheduled background attempt, or {@link Duration#ZERO}. */
    public Duration nextReconnectDelay() {
        return nextReconnectDelay;
    }

    /** Accepted application commands awaiting response draining on the current shared connection. */
    public int inFlightCommands() {
        return inFlightCommands;
    }

    /** Encoded application-command bytes still awaiting socket write. */
    public long queuedWriteBytes() {
        return queuedWriteBytes;
    }

    /** Locally rejected commands on the current shared physical connection. */
    public long connectionBackpressureRejections() {
        return connectionBackpressureRejections;
    }
}
