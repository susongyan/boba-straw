package io.github.susongyan.bobastraw;

/** A typed position in one batch, not a Future or an independently cancellable command. */
public final class BobaStrawCommandHandle<T> {
    final Object owner;
    final int index;
    final CommandDecoder<T> decoder;

    BobaStrawCommandHandle(Object owner, int index, CommandDecoder<T> decoder) {
        this.owner = owner;
        this.index = index;
        this.decoder = decoder;
    }
}
