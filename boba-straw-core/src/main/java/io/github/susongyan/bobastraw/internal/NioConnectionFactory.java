package io.github.susongyan.bobastraw.internal;

import io.github.susongyan.bobastraw.ProtocolVersion;
import io.github.susongyan.bobastraw.protocol.RespLimits;
import io.github.susongyan.bobastraw.protocol.RespValue;

import java.time.Duration;
import java.util.function.Consumer;

/** Creates connections bound to one loop from a shared EventLoopGroup. */
public final class NioConnectionFactory {
    private final NioEventLoopGroup eventLoops;

    public NioConnectionFactory(NioEventLoopGroup eventLoops) {
        this.eventLoops = eventLoops;
    }

    public NioConnection create(
        String host,
        int port,
        Duration timeout,
        ProtocolVersion requestedProtocol,
        String username,
        String password,
        String clientName,
        Consumer<RespValue> pushListener,
        Duration idlePingInterval
    ) {
        return create(host, port, timeout, requestedProtocol, username, password, clientName,
            pushListener, idlePingInterval, RespLimits.defaults());
    }

    /**
     * Creates a connection with the caller's RESP resource limits.
     *
     * <p>Limits belong to a client, rather than to the shared EventLoop resources, so clients
     * that share selector threads can still enforce different reply-size policies.</p>
     */
    public NioConnection create(
        String host,
        int port,
        Duration timeout,
        ProtocolVersion requestedProtocol,
        String username,
        String password,
        String clientName,
        Consumer<RespValue> pushListener,
        Duration idlePingInterval,
        RespLimits respLimits
    ) {
        return new NioConnection(
            eventLoops.next(), host, port, timeout, requestedProtocol, username, password,
            clientName, pushListener, idlePingInterval, respLimits
        );
    }
}
