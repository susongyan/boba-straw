package io.github.susongyan.bobastraw.internal;

import io.github.susongyan.bobastraw.ProtocolVersion;
import io.github.susongyan.bobastraw.BobaStrawConnectionLimits;
import io.github.susongyan.bobastraw.protocol.RespLimits;
import io.github.susongyan.bobastraw.protocol.RespValue;

import java.time.Duration;
import java.util.function.Consumer;

/** Creates connections bound to one loop from a shared EventLoopGroup. */
public final class NioConnectionFactory {
    /** Cancellable lifecycle task scheduled independently from a physical connection. */
    public interface ScheduledTask {
        boolean cancel();
    }

    private final NioEventLoopGroup eventLoops;
    private final BobaCallbackDispatcher callbackDispatcher;

    public NioConnectionFactory(NioEventLoopGroup eventLoops) {
        this(eventLoops, null);
    }

    public NioConnectionFactory(
        NioEventLoopGroup eventLoops,
        BobaCallbackDispatcher callbackDispatcher
    ) {
        this.eventLoops = eventLoops;
        this.callbackDispatcher = callbackDispatcher;
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
        return create(host, port, timeout, requestedProtocol, username, password, clientName,
            pushListener, idlePingInterval, respLimits, BobaStrawConnectionLimits.defaults());
    }

    /**
     * Creates a connection with client-owned reply and command-admission limits.
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
        RespLimits respLimits,
        BobaStrawConnectionLimits connectionLimits
    ) {
        return new NioConnection(
            eventLoops.next(), host, port, timeout, requestedProtocol, username, password,
            clientName, pushListener, idlePingInterval, respLimits, connectionLimits, callbackDispatcher
        );
    }

    /** Schedules client lifecycle work without tying it to a socket that may already be closed. */
    public ScheduledTask schedule(Runnable action, Duration delay) {
        if (action == null) {
            throw new IllegalArgumentException("scheduled action must not be null");
        }
        if (delay == null || delay.isNegative()) {
            throw new IllegalArgumentException("scheduled delay must not be negative");
        }
        final NioEventLoop.ScheduledTask scheduled =
            eventLoops.next().schedule(action, delay.toNanos());
        return new ScheduledTask() {
            @Override
            public boolean cancel() {
                return scheduled.cancel();
            }
        };
    }
}
