package io.github.susongyan.bobastraw;

/** Current lifecycle state of a client's shared Redis connection. */
public enum BobaStrawConnectionState {
    /** A TCP connection exists and is completing connect, protocol negotiation, or authentication. */
    CONNECTING,
    /** The connection completed its handshake and accepts application commands. */
    READY,
    /** The prior connection failed and the client is waiting before its next background attempt. */
    BACKING_OFF,
    /** The client was closed or its shared resources are no longer available. */
    CLOSED
}
