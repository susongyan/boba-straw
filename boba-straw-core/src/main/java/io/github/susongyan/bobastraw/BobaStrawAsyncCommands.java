package io.github.susongyan.bobastraw;

import io.github.susongyan.bobastraw.protocol.RespValue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionStage;

/**
 * Standard Java 8 asynchronous String command API; no reactive-library dependency.
 * BLPOP/BRPOP use dedicated connections; Cluster keys must share a slot.
 * Cluster no-key operations address one primary, not the entire cluster.
 */
public final class BobaStrawAsyncCommands {
    private final CommandExecutor executor;
    private final java.util.function.Function<String[], CompletionStage<RespValue>> blocking;

    BobaStrawAsyncCommands(BobaStrawClient client) {
        this.executor = client::executeAsync;
        this.blocking = command -> client.executeBlocking(command, false);
    }

    BobaStrawAsyncCommands(CommandExecutor executor) {
        this(executor, command -> {
            throw new UnsupportedOperationException("No dedicated blocking executor");
        });
    }

    BobaStrawAsyncCommands(CommandExecutor executor,
                          java.util.function.Function<String[], CompletionStage<RespValue>> blocking) {
        this.executor = executor;
        this.blocking = blocking;
    }

    public CompletionStage<Long> bitCount(String key) {
        return typed("BITCOUNT", CommandDecoders.LONG, key);
    }

    public CompletionStage<Long> bitCount(String key, long start, long end) {
        return typed("BITCOUNT", CommandDecoders.LONG, key, Long.toString(start), Long.toString(end));
    }

    public CompletionStage<List<String>> hmget(String key, String... fields) {
        return typed("HMGET", BobaStrawAsyncCommands::stringList, prepend(key, fields));
    }

    public CompletionStage<Long> hdel(String key, String... fields) {
        return typed("HDEL", CommandDecoders.LONG, prepend(key, fields));
    }

    public CompletionStage<Boolean> hexists(String key, String field) {
        return typed("HEXISTS", CommandDecoders.BOOLEAN, key, field);
    }

    public CompletionStage<Long> hlen(String key) {
        return typed("HLEN", CommandDecoders.LONG, key);
    }

    public CompletionStage<Long> hincrBy(String key, String field, long amount) {
        return typed("HINCRBY", CommandDecoders.LONG, key, field, Long.toString(amount));
    }

    public CompletionStage<String> lpop(String key) {
        return typed("LPOP", CommandDecoders.STRING, key);
    }

    public CompletionStage<String> rpop(String key) {
        return typed("RPOP", CommandDecoders.STRING, key);
    }

    public CompletionStage<Long> llen(String key) {
        return typed("LLEN", CommandDecoders.LONG, key);
    }

    public CompletionStage<Long> srem(String key, String... members) {
        return typed("SREM", CommandDecoders.LONG, prepend(key, members));
    }

    public CompletionStage<Long> scard(String key) {
        return typed("SCARD", CommandDecoders.LONG, key);
    }

    public CompletionStage<Boolean> sismember(String key, String member) {
        return typed("SISMEMBER", CommandDecoders.BOOLEAN, key, member);
    }

    public CompletionStage<Long> zrem(String key, String... members) {
        return typed("ZREM", CommandDecoders.LONG, prepend(key, members));
    }

    public CompletionStage<Double> zscore(String key, String member) {
        return typed("ZSCORE", CommandDecoders.NULLABLE_DOUBLE, key, member);
    }

    public CompletionStage<Long> zcard(String key) {
        return typed("ZCARD", CommandDecoders.LONG, key);
    }

    public CompletionStage<Long> zrank(String key, String member) {
        return typed("ZRANK", CommandDecoders.NULLABLE_LONG, key, member);
    }

    private <T> CompletionStage<T> typed(String command, CommandDecoder<T> decoder, String... arguments) {
        return executor.execute(new TypedCommand<T>(command, decoder, arguments));
    }

    public CompletionStage<String> ping() {
        return typed("PING", CommandDecoders.STRING);
    }

    /** Returns [key, value], or an empty list on server timeout. Client commandTimeout still applies. */
    public CompletionStage<List<String>> blpop(long timeoutSeconds, String... keys) {
        return BobaStrawStages.map(
            blocking.apply(blockingPopArguments("BLPOP", timeoutSeconds, keys)),
            BobaStrawAsyncCommands::stringList
        );
    }

    /** Like blpop, but removes the last element. Uses its own dedicated connection. */
    public CompletionStage<List<String>> brpop(long timeoutSeconds, String... keys) {
        return BobaStrawStages.map(
            blocking.apply(blockingPopArguments("BRPOP", timeoutSeconds, keys)),
            BobaStrawAsyncCommands::stringList
        );
    }

    static String[] blockingPopArguments(String command, long timeoutSeconds, String[] keys) {
        if (timeoutSeconds < 0 || keys == null || keys.length == 0) {
            throw new IllegalArgumentException("Blocking pop requires keys and a non-negative timeout");
        }
        String[] arguments = new String[keys.length + 2];
        arguments[0] = command;
        for (int index = 0; index < keys.length; index++) {
            if (keys[index] == null) {
                throw new IllegalArgumentException("Blocking pop keys must not be null");
            }
            arguments[index + 1] = keys[index];
        }
        arguments[arguments.length - 1] = Long.toString(timeoutSeconds);
        return arguments;
    }

    public CompletionStage<String> get(String key) {
        return typed("GET", CommandDecoders.STRING, key);
    }

    public CompletionStage<String> set(String key, String value) {
        return typed("SET", CommandDecoders.STRING, key, value);
    }

    public CompletionStage<String> set(String key, String value, SetArgs options) {
        if (options == null) {
            throw new IllegalArgumentException("SET options must not be null");
        }
        return typed("SET", CommandDecoders.STRING, join(key, value, options.arguments()));
    }

    public CompletionStage<Long> del(String... keys) {
        return typed("DEL", CommandDecoders.LONG, keys);
    }

    public CompletionStage<Long> unlink(String... keys) {
        return typed("UNLINK", CommandDecoders.LONG, keys);
    }

    public CompletionStage<Boolean> exists(String key) {
        return typed("EXISTS", CommandDecoders.BOOLEAN, key);
    }

    public CompletionStage<Long> existsCount(String... keys) {
        return typed("EXISTS", CommandDecoders.LONG, keys);
    }

    public CompletionStage<String> type(String key) {
        return typed("TYPE", CommandDecoders.STRING, key);
    }

    public CompletionStage<Long> expire(String key, long seconds) {
        return typed("EXPIRE", CommandDecoders.LONG, key, Long.toString(seconds));
    }

    public CompletionStage<Long> expireAt(String key, long unixSeconds) {
        return typed("EXPIREAT", CommandDecoders.LONG, key, Long.toString(unixSeconds));
    }

    public CompletionStage<Long> pexpire(String key, long milliseconds) {
        return typed("PEXPIRE", CommandDecoders.LONG, key, Long.toString(milliseconds));
    }

    public CompletionStage<Long> pexpireAt(String key, long unixMilliseconds) {
        return typed("PEXPIREAT", CommandDecoders.LONG, key, Long.toString(unixMilliseconds));
    }

    public CompletionStage<Long> persist(String key) {
        return typed("PERSIST", CommandDecoders.LONG, key);
    }

    public CompletionStage<Long> ttl(String key) {
        return typed("TTL", CommandDecoders.LONG, key);
    }

    public CompletionStage<Long> pttl(String key) {
        return typed("PTTL", CommandDecoders.LONG, key);
    }

    public CompletionStage<String> rename(String key, String newKey) {
        return typed("RENAME", CommandDecoders.STRING, key, newKey);
    }

    public CompletionStage<Boolean> renameNx(String key, String newKey) {
        return typed("RENAMENX", CommandDecoders.BOOLEAN, key, newKey);
    }

    public CompletionStage<Long> touch(String... keys) {
        return typed("TOUCH", CommandDecoders.LONG, keys);
    }

    public CompletionStage<List<String>> keys(String pattern) {
        return typed("KEYS", BobaStrawAsyncCommands::stringList, pattern);
    }

    public CompletionStage<String> randomKey() {
        return typed("RANDOMKEY", CommandDecoders.STRING);
    }

    public CompletionStage<Long> incr(String key) {
        return typed("INCR", CommandDecoders.LONG, key);
    }

    public CompletionStage<Long> incrBy(String key, long increment) {
        return typed("INCRBY", CommandDecoders.LONG, key, Long.toString(increment));
    }

    public CompletionStage<Double> incrByFloat(String key, double increment) {
        return typed("INCRBYFLOAT", BobaStrawAsyncCommands::asDouble, key, Double.toString(increment));
    }

    public CompletionStage<Long> decr(String key) {
        return typed("DECR", CommandDecoders.LONG, key);
    }

    public CompletionStage<Long> decrBy(String key, long decrement) {
        return typed("DECRBY", CommandDecoders.LONG, key, Long.toString(decrement));
    }

    public CompletionStage<Long> append(String key, String value) {
        return typed("APPEND", CommandDecoders.LONG, key, value);
    }

    public CompletionStage<Long> strlen(String key) {
        return typed("STRLEN", CommandDecoders.LONG, key);
    }

    public CompletionStage<String> getSet(String key, String value) {
        return typed("GETSET", CommandDecoders.STRING, key, value);
    }

    public CompletionStage<List<String>> mget(String... keys) {
        return typed("MGET", BobaStrawAsyncCommands::stringList, keys);
    }

    public CompletionStage<String> mset(Map<String, String> values) {
        return typed("MSET", CommandDecoders.STRING, pairs(values));
    }

    public CompletionStage<Boolean> msetNx(Map<String, String> values) {
        return typed("MSETNX", CommandDecoders.BOOLEAN, pairs(values));
    }

    public CompletionStage<Boolean> setNx(String key, String value) {
        return typed("SETNX", CommandDecoders.BOOLEAN, key, value);
    }

    public CompletionStage<String> setEx(String key, long seconds, String value) {
        return typed("SETEX", CommandDecoders.STRING, key, Long.toString(seconds), value);
    }

    public CompletionStage<String> psetEx(String key, long milliseconds, String value) {
        return typed("PSETEX", CommandDecoders.STRING, key, Long.toString(milliseconds), value);
    }

    public CompletionStage<String> getRange(String key, long start, long end) {
        return typed("GETRANGE", CommandDecoders.STRING, key, Long.toString(start), Long.toString(end));
    }

    public CompletionStage<Long> setRange(String key, long offset, String value) {
        return typed("SETRANGE", CommandDecoders.LONG, key, Long.toString(offset), value);
    }

    public CompletionStage<Long> getBit(String key, long offset) {
        return typed("GETBIT", CommandDecoders.LONG, key, Long.toString(offset));
    }

    public CompletionStage<Long> setBit(String key, long offset, long value) {
        if (value != 0 && value != 1) {
            throw new IllegalArgumentException("Redis bit values must be 0 or 1");
        }
        return typed("SETBIT", CommandDecoders.LONG, key, Long.toString(offset), Long.toString(value));
    }

    public CompletionStage<String> hget(String key, String field) {
        return typed("HGET", CommandDecoders.STRING, key, field);
    }

    public CompletionStage<Long> hset(String key, String field, String value) {
        return typed("HSET", CommandDecoders.LONG, key, field, value);
    }

    public CompletionStage<Map<String, String>> hgetall(String key) {
        return typed("HGETALL", BobaStrawAsyncCommands::stringMap, key);
    }

    public CompletionStage<Long> lpush(String key, String... values) {
        return typed("LPUSH", CommandDecoders.LONG, prepend(key, values));
    }

    public CompletionStage<Long> rpush(String key, String... values) {
        return typed("RPUSH", CommandDecoders.LONG, prepend(key, values));
    }

    public CompletionStage<List<String>> lrange(String key, long start, long stop) {
        return typed("LRANGE", BobaStrawAsyncCommands::stringList, key, Long.toString(start), Long.toString(stop));
    }

    public CompletionStage<Long> sadd(String key, String... members) {
        return typed("SADD", CommandDecoders.LONG, prepend(key, members));
    }

    public CompletionStage<Set<String>> smembers(String key) {
        return typed("SMEMBERS", BobaStrawAsyncCommands::stringSet, key);
    }

    public CompletionStage<Long> zadd(String key, double score, String member) {
        return typed("ZADD", CommandDecoders.LONG, key, Double.toString(score), member);
    }

    public CompletionStage<List<String>> zrange(String key, long start, long stop) {
        return typed("ZRANGE", BobaStrawAsyncCommands::stringList, key, Long.toString(start), Long.toString(stop));
    }

    public CompletionStage<RespValue> eval(String script, String[] keys, String... arguments) {
        return eval(script, ScriptOutput.raw(), keys, arguments);
    }

    /** Executes once; all accessed keys must be declared, and Cluster keys must share a slot. */
    public <T> CompletionStage<T> eval(String script, ScriptOutput<T> output,
                                      String[] keys, String... arguments) {
        return executor.execute(ScriptCommandFactory.text(false, script, output, keys, arguments));
    }

    /** Executes once by digest. NOSCRIPT remains an error; no automatic loading or retry. */
    public CompletionStage<RespValue> evalSha(String sha1, String[] keys, String... arguments) {
        return evalSha(sha1, ScriptOutput.raw(), keys, arguments);
    }

    /** Executes once by digest and decodes the reply using the explicit output contract. */
    public <T> CompletionStage<T> evalSha(String sha1, ScriptOutput<T> output,
                                         String[] keys, String... arguments) {
        return executor.execute(ScriptCommandFactory.text(true, sha1, output, keys, arguments));
    }

    /**
     * Loads without executing. In Cluster this selects ONE primary, not all nodes;
     * use the Cluster client's scriptLoadForKey for key-directed preloading.
     * A later failover or cache flush can still cause NOSCRIPT.
     */
    public CompletionStage<String> scriptLoad(String script) {
        return executor.execute(ScriptCommandFactory.load(script));
    }

    static CompletionStage<String> string(CompletionStage<RespValue> stage) {
        return BobaStrawStages.map(stage, RespValue::asString);
    }

    static CompletionStage<Long> number(CompletionStage<RespValue> stage) {
        return BobaStrawStages.map(stage, RespValue::asLong);
    }

    static CompletionStage<Boolean> booleanNumber(CompletionStage<RespValue> stage) {
        return BobaStrawStages.map(number(stage), value -> value.longValue() != 0L);
    }

    static CompletionStage<Double> doubleNumber(CompletionStage<RespValue> stage) {
        return BobaStrawStages.map(stage, BobaStrawAsyncCommands::asDouble);
    }

    private static String[] prepend(String first, String[] values) {
        String[] command = new String[values.length + 1];
        command[0] = first;
        System.arraycopy(values, 0, command, 1, values.length);
        return command;
    }

    private static String[] join(String first, String second, String[] tail) {
        String[] command = new String[tail.length + 2];
        command[0] = first;
        command[1] = second;
        System.arraycopy(tail, 0, command, 2, tail.length);
        return command;
    }

    private static String[] pairs(Map<String, String> values) {
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException("Redis multi-value commands require at least one entry");
        }
        String[] command = new String[values.size() * 2];
        int index = 0;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            command[index++] = entry.getKey();
            command[index++] = entry.getValue();
        }
        return command;
    }

    private static double asDouble(RespValue value) {
        if (value instanceof RespValue.DoubleValue) {
            return ((RespValue.DoubleValue) value).value;
        }
        return Double.parseDouble(value.asString());
    }

    static List<String> stringList(RespValue value) {
        if (value instanceof RespValue.Null) {
            return new ArrayList<String>();
        }
        if (!(value instanceof RespValue.Array)) {
            throw new IllegalStateException("Expected RESP array but got " + value.getClass().getSimpleName());
        }
        List<String> result = new ArrayList<String>();
        for (RespValue item : ((RespValue.Array) value).values) {
            result.add(item.asString());
        }
        return result;
    }

    static Set<String> stringSet(RespValue value) {
        List<RespValue> values;
        if (value instanceof RespValue.SetValue) {
            values = ((RespValue.SetValue) value).values;
        } else if (value instanceof RespValue.Array) {
            values = ((RespValue.Array) value).values;
        } else if (value instanceof RespValue.Null) {
            values = new ArrayList<RespValue>();
        } else {
            throw new IllegalStateException("Expected RESP set or array but got " + value.getClass().getSimpleName());
        }
        Set<String> result = new LinkedHashSet<String>();
        for (RespValue item : values) {
            result.add(item.asString());
        }
        return result;
    }

    static Map<String, String> stringMap(RespValue value) {
        Map<String, String> result = new LinkedHashMap<String, String>();
        if (value instanceof RespValue.MapValue) {
            for (Map.Entry<RespValue, RespValue> entry : ((RespValue.MapValue) value).values.entrySet()) {
                result.put(entry.getKey().asString(), entry.getValue().asString());
            }
            return result;
        }
        if (value instanceof RespValue.Array) {
            List<RespValue> values = ((RespValue.Array) value).values;
            if (values.size() % 2 != 0) {
                throw new IllegalStateException("HGETALL returned an odd number of values");
            }
            for (int index = 0; index < values.size(); index += 2) {
                result.put(values.get(index).asString(), values.get(index + 1).asString());
            }
            return result;
        }
        throw new IllegalStateException("Expected RESP map or array but got " + value.getClass().getSimpleName());
    }
}
