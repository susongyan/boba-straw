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
    private final BobaStrawScripts scripts;

    BobaStrawBatchCommands(Enqueuer enqueuer) {
        this(enqueuer, null);
    }

    BobaStrawBatchCommands(Enqueuer enqueuer, BobaStrawScripts scripts) {
        this.enqueuer = enqueuer;
        this.scripts = scripts;
    }

    /** Queues EVAL locally; execution uses the enclosing batch lifecycle. */
    public <T> BobaStrawCommandHandle<T> eval(String script, ScriptOutput<T> output,
                                            String[] keys, String... arguments) {
        return enqueuer.enqueue(ScriptCommandFactory.text(false, script, output, keys, arguments));
    }

    /** Explicit EVALSHA; NOSCRIPT remains an individual result error, without recovery. */
    public <T> BobaStrawCommandHandle<T> evalSha(String sha, ScriptOutput<T> output,
                                               String[] keys, String... arguments) {
        return enqueuer.enqueue(ScriptCommandFactory.text(true, sha, output, keys, arguments));
    }

    /** Queues SCRIPT LOAD; its returned SHA is available only after the batch completes. */
    public BobaStrawCommandHandle<String> scriptLoad(String script) {
        return enqueuer.enqueue(ScriptCommandFactory.load(script));
    }

    /** Captures a registered UTF-8 script now and queues EVAL, never cached EVALSHA. */
    public <T> BobaStrawCommandHandle<T> script(String name, ScriptOutput<T> output,
                                              String[] keys, String... arguments) {
        if (scripts == null) {
            throw new IllegalStateException("No client script registry");
        }
        return enqueuer.enqueue(scripts.batchCommand(name, output, keys, arguments));
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
