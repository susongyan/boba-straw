package io.github.susongyan.bobastraw;

/** Handle for a dedicated Pub/Sub connection. */
public interface BobaStrawSubscription extends AutoCloseable {
    @Override
    void close();
}
