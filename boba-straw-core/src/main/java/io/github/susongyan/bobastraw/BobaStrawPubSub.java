package io.github.susongyan.bobastraw;

import io.github.susongyan.bobastraw.internal.NioConnection;
import io.github.susongyan.bobastraw.protocol.RespValue;

import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Pub/Sub facade backed by a connection dedicated to subscriptions. */
public final class BobaStrawPubSub {
    private final java.util.function.Function<Consumer<RespValue>, Binding> opener;

    BobaStrawPubSub(BobaStrawClient client) {
        this(listener -> new Binding(client, client.openPubSubConnection(listener)));
    }

    BobaStrawPubSub(java.util.function.Function<Consumer<RespValue>, Binding> opener) {
        this.opener = opener;
    }

    static final class Binding {
        final BobaStrawClient client;
        final NioConnection connection;

        Binding(BobaStrawClient client, NioConnection connection) {
            this.client = client;
            this.connection = connection;
        }
    }

    public CompletionStage<BobaStrawSubscription> subscribe(
        String channel, Consumer<String> listener
    ) {
        Binding binding = opener.apply(value -> {
            if (value instanceof RespValue.Array) {
                java.util.List<RespValue> values = ((RespValue.Array) value).values;
                if (values.size() >= 3 && "message".equals(values.get(0).asString())) {
                    listener.accept(values.get(2).asString());
                }
            } else if (value instanceof RespValue.Push) {
                java.util.List<RespValue> values = ((RespValue.Push) value).values;
                if (values.size() >= 3 && "message".equals(values.get(0).asString())) {
                    listener.accept(values.get(2).asString());
                }
            }
        });
        BobaStrawClient client = binding.client;
        NioConnection connection = binding.connection;
        CompletionStage<BobaStrawSubscription> subscription = BobaStrawStages.map(
            client.executeOn(connection, "SUBSCRIBE", channel),
            ignored -> new BobaStrawSubscription() {
                private final AtomicBoolean closed = new AtomicBoolean();
                private final java.util.concurrent.CompletableFuture<Void> terminated =
                    observeTermination(connection, closed);

                @Override
                public CompletionStage<Void> termination() {
                    return client.exposeCompletion(terminated);
                }

                @Override
                public void close() {
                    if (!closed.compareAndSet(false, true)) {
                        return;
                    }
                    connection.executeAfterPushCallbacks(
                        new String[] { "UNSUBSCRIBE", channel },
                        new Runnable() {
                            @Override
                            public void run() {
                                client.onDedicatedPushCallbacksDrained(connection);
                            }
                        }
                    )
                        .whenComplete((value, error) -> {
                            if (error == null) {
                                client.closeDedicatedAfterAcknowledgement(connection);
                            } else {
                                client.closeDedicated(connection);
                            }
                        });
                }
            }
        );
        subscription.whenComplete((value, error) -> {
            if (error != null) {
                client.closeDedicated(connection);
            }
        });
        return subscription;
    }

    public CompletionStage<BobaStrawSubscription> psubscribe(
        String pattern, Consumer<String> listener
    ) {
        Binding binding = opener.apply(value -> {
            if (value instanceof RespValue.Array || value instanceof RespValue.Push) {
                java.util.List<RespValue> values = value instanceof RespValue.Array
                    ? ((RespValue.Array) value).values
                    : ((RespValue.Push) value).values;
                if (values.size() >= 4 && "pmessage".equals(values.get(0).asString())) {
                    listener.accept(values.get(3).asString());
                }
            }
        });
        BobaStrawClient client = binding.client;
        NioConnection connection = binding.connection;
        CompletionStage<BobaStrawSubscription> subscription = BobaStrawStages.map(
            client.executeOn(connection, "PSUBSCRIBE", pattern),
            ignored -> new BobaStrawSubscription() {
                private final AtomicBoolean closed = new AtomicBoolean();
                private final java.util.concurrent.CompletableFuture<Void> terminated =
                    observeTermination(connection, closed);

                @Override
                public CompletionStage<Void> termination() {
                    return client.exposeCompletion(terminated);
                }

                @Override
                public void close() {
                    if (!closed.compareAndSet(false, true)) {
                        return;
                    }
                    connection.executeAfterPushCallbacks(
                        new String[] { "PUNSUBSCRIBE", pattern },
                        new Runnable() {
                            @Override
                            public void run() {
                                client.onDedicatedPushCallbacksDrained(connection);
                            }
                        }
                    )
                        .whenComplete((value, error) -> {
                            if (error == null) {
                                client.closeDedicatedAfterAcknowledgement(connection);
                            } else {
                                client.closeDedicated(connection);
                            }
                        });
                }
            }
        );
        subscription.whenComplete((value, error) -> {
            if (error != null) {
                client.closeDedicated(connection);
            }
        });
        return subscription;
    }
    private static java.util.concurrent.CompletableFuture<Void> observeTermination(
        NioConnection connection, AtomicBoolean requestedClose
    ) {
        java.util.concurrent.CompletableFuture<Void> result = new java.util.concurrent.CompletableFuture<Void>();
        connection.onClose(() -> {
            if (requestedClose.get()) {
                result.complete(null);
            } else {
                result.completeExceptionally(new BobaStrawConnectionException(
                    "Subscription connection terminated; resubscription is explicit and messages may be lost"));
            }
        });
        return result;
    }
}
