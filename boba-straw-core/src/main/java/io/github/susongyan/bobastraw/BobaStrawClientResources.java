package io.github.susongyan.bobastraw;

import io.github.susongyan.bobastraw.internal.BobaCallbackDispatcher;
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
    private final BobaCallbackDispatcher callbacks;
    private final NioConnectionFactory connectionFactory;

    private BobaStrawClientResources(Builder builder) {
        NioEventLoopGroup createdEventLoops = new NioEventLoopGroup(builder.eventLoopThreads);
        try {
            BobaCallbackDispatcher createdCallbacks = new BobaCallbackDispatcher(
                builder.callbackThreads,
                builder.callbackQueueCapacity
            );
            this.eventLoops = createdEventLoops;
            this.callbacks = createdCallbacks;
            this.connectionFactory = new NioConnectionFactory(eventLoops, callbacks);
        } catch (RuntimeException error) {
            createdEventLoops.close();
            throw error;
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public int eventLoopThreads() {
        return eventLoops.size();
    }

    /** Number of bounded worker threads used for application-visible callbacks. */
    public int callbackThreads() {
        return callbacks.threadCount();
    }

    /** Maximum callbacks queued behind active callback workers. */
    public int callbackQueueCapacity() {
        return callbacks.queueCapacity();
    }

    public boolean isOpen() {
        return eventLoops.isOpen() && callbacks.isOpen();
    }

    NioConnectionFactory connectionFactory() {
        return connectionFactory;
    }

    @Override
    public void close() {
        eventLoops.close();
        callbacks.close();
    }

    public static final class Builder {
        private int eventLoopThreads = 1;
        private int callbackThreads = 1;
        private int callbackQueueCapacity = 1024;

        public Builder eventLoopThreads(int value) {
            if (value < 1) {
                throw new IllegalArgumentException("eventLoopThreads must be positive");
            }
            this.eventLoopThreads = value;
            return this;
        }

        /**
         * Sets callback worker count. Redis I/O still runs only on the selector EventLoops.
         */
        public Builder callbackThreads(int value) {
            if (value < 1) {
                throw new IllegalArgumentException("callbackThreads must be positive");
            }
            this.callbackThreads = value;
            return this;
        }

        /**
         * Sets the bounded callback backlog shared by clients using these Resources.
         */
        public Builder callbackQueueCapacity(int value) {
            if (value < 1) {
                throw new IllegalArgumentException("callbackQueueCapacity must be positive");
            }
            this.callbackQueueCapacity = value;
            return this;
        }

        public BobaStrawClientResources build() {
            return new BobaStrawClientResources(this);
        }
    }
}
