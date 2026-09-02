package io.github.susongyan.bobastraw;

import io.github.susongyan.bobastraw.internal.NioConnection;
import io.github.susongyan.bobastraw.protocol.RespValue;

import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

/** Pub/Sub facade backed by a connection dedicated to subscriptions. */
public final class BobaStrawPubSub {
    private final BobaStrawClient client;

    BobaStrawPubSub(BobaStrawClient client) {
        this.client = client;
    }

    public CompletionStage<BobaStrawSubscription> subscribe(
        String channel, Consumer<String> listener
    ) {
        NioConnection connection = client.openPubSubConnection(value -> {
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
        CompletionStage<BobaStrawSubscription> subscription = client.executeOn(connection, "SUBSCRIBE", channel)
            .thenApply(ignored -> new BobaStrawSubscription() {
                @Override
                public void close() {
                    client.executeOn(connection, "UNSUBSCRIBE", channel)
                        .whenComplete((value, error) -> client.closeDedicated(connection));
                }
        });
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
        NioConnection connection = client.openPubSubConnection(value -> {
            if (value instanceof RespValue.Array || value instanceof RespValue.Push) {
                java.util.List<RespValue> values = value instanceof RespValue.Array
                    ? ((RespValue.Array) value).values
                    : ((RespValue.Push) value).values;
                if (values.size() >= 4 && "pmessage".equals(values.get(0).asString())) {
                    listener.accept(values.get(3).asString());
                }
            }
        });
        CompletionStage<BobaStrawSubscription> subscription = client.executeOn(connection, "PSUBSCRIBE", pattern)
            .thenApply(ignored -> new BobaStrawSubscription() {
                @Override
                public void close() {
                    client.executeOn(connection, "PUNSUBSCRIBE", pattern)
                        .whenComplete((value, error) -> client.closeDedicated(connection));
                }
        });
        subscription.whenComplete((value, error) -> {
            if (error != null) {
                client.closeDedicated(connection);
            }
        });
        return subscription;
    }
}
