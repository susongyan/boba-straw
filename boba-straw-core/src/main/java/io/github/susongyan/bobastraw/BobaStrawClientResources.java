package io.github.susongyan.bobastraw;

import io.github.susongyan.bobastraw.internal.NioConnectionFactory;
import io.github.susongyan.bobastraw.internal.NioEventLoopGroup;

/**
 * Shareable owner of Boba Straw selector threads.
 *
 * <p>A client that receives Resources from its builder does not close them. Callers should
 * close externally owned Resources after every client using them has been closed.</p>
 */
public final class BobaStrawClientResources implements AutoCloseable {
    private final NioEventLoopGroup eventLoops;
    private final NioConnectionFactory connectionFactory;

    private BobaStrawClientResources(Builder builder) {
        this.eventLoops = new NioEventLoopGroup(builder.eventLoopThreads);
        this.connectionFactory = new NioConnectionFactory(eventLoops);
    }

    public static Builder builder() {
        return new Builder();
    }

    public int eventLoopThreads() {
        return eventLoops.size();
    }

    public boolean isOpen() {
        return eventLoops.isOpen();
    }

    NioConnectionFactory connectionFactory() {
        return connectionFactory;
    }

    @Override
    public void close() {
        eventLoops.close();
    }

    public static final class Builder {
        private int eventLoopThreads = 1;

        public Builder eventLoopThreads(int value) {
            if (value < 1) {
                throw new IllegalArgumentException("eventLoopThreads must be positive");
            }
            this.eventLoopThreads = value;
            return this;
        }

        public BobaStrawClientResources build() {
            return new BobaStrawClientResources(this);
        }
    }
}
