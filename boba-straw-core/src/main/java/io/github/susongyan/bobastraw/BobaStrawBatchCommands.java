package io.github.susongyan.bobastraw;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** High-frequency String commands queued locally for Pipeline or transaction execution. */
public final class BobaStrawBatchCommands {
    interface Enqueuer {
        <T> BobaStrawCommandHandle<T> enqueue(TypedCommand<T> command);
    }

    private final Enqueuer enqueuer;

    BobaStrawBatchCommands(Enqueuer enqueuer) {
        this.enqueuer = enqueuer;
    }

    public BobaStrawCommandHandle<String> get(String key) {
        return add("GET", CommandDecoders.STRING, key);
    }

    public BobaStrawCommandHandle<String> set(String key, String value) {
        return add("SET", CommandDecoders.STRING, key, value);
    }

    public BobaStrawCommandHandle<Long> del(String... keys) {
        return add("DEL", CommandDecoders.LONG, keys);
    }

    public BobaStrawCommandHandle<Boolean> exists(String key) {
        return add("EXISTS", CommandDecoders.BOOLEAN, key);
    }

    public BobaStrawCommandHandle<Long> incr(String key) {
        return add("INCR", CommandDecoders.LONG, key);
    }

    public BobaStrawCommandHandle<Long> ttl(String key) {
        return add("TTL", CommandDecoders.LONG, key);
    }

    public BobaStrawCommandHandle<List<String>> mget(String... keys) {
        return add("MGET", BobaStrawAsyncCommands::stringList, keys);
    }

    public BobaStrawCommandHandle<String> hget(String key, String field) {
        return add("HGET", CommandDecoders.STRING, key, field);
    }

    public BobaStrawCommandHandle<Long> hset(String key, String field, String value) {
        return add("HSET", CommandDecoders.LONG, key, field, value);
    }

    public BobaStrawCommandHandle<Map<String, String>> hgetall(String key) {
        return add("HGETALL", BobaStrawAsyncCommands::stringMap, key);
    }

    public BobaStrawCommandHandle<Long> lpush(String key, String... values) {
        return add("LPUSH", CommandDecoders.LONG, prepend(key, values));
    }

    public BobaStrawCommandHandle<List<String>> lrange(String key, long start, long stop) {
        return add("LRANGE", BobaStrawAsyncCommands::stringList, key, Long.toString(start), Long.toString(stop));
    }

    public BobaStrawCommandHandle<Long> sadd(String key, String... values) {
        return add("SADD", CommandDecoders.LONG, prepend(key, values));
    }

    public BobaStrawCommandHandle<Set<String>> smembers(String key) {
        return add("SMEMBERS", BobaStrawAsyncCommands::stringSet, key);
    }

    public BobaStrawCommandHandle<Long> zadd(String key, double score, String member) {
        return add("ZADD", CommandDecoders.LONG, key, Double.toString(score), member);
    }

    public BobaStrawCommandHandle<Double> zscore(String key, String member) {
        return add("ZSCORE", CommandDecoders.NULLABLE_DOUBLE, key, member);
    }

    private <T> BobaStrawCommandHandle<T> add(String name, CommandDecoder<T> decoder, String... arguments) {
        return enqueuer.enqueue(new TypedCommand<T>(name, decoder, arguments));
    }

    private static String[] prepend(String key, String[] values) {
        if (values == null || values.length == 0) {
            throw new IllegalArgumentException("At least one value is required");
        }
        String[] arguments = new String[values.length + 1];
        arguments[0] = key;
        System.arraycopy(values, 0, arguments, 1, values.length);
        return arguments;
    }
}
