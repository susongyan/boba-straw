package io.github.susongyan.bobastraw;

/** Handle for a dedicated Pub/Sub connection. */
public interface BobaStrawSubscription extends AutoCloseable {
    /** Completes on transport termination; unexpected disconnects complete exceptionally. */
    default java.util.concurrent.CompletionStage<Void> termination() {
        throw new UnsupportedOperationException("Termination observation is not provided by this implementation");
    }

    @Override
    void close();
}
