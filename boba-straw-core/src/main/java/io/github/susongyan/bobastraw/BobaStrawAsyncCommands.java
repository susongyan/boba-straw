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

/** Standard Java 8 asynchronous command API; no reactive-library dependency. */
public final class BobaStrawAsyncCommands {
    private final BobaStrawClient client;

    BobaStrawAsyncCommands(BobaStrawClient client) {
        this.client = client;
    }

    public CompletionStage<String> ping() {
        return string(client.executeAsync("PING"));
    }

    public CompletionStage<String> get(String key) {
        return string(client.executeAsync("GET", key));
    }

    public CompletionStage<String> set(String key, String value) {
        return string(client.executeAsync("SET", key, value));
    }

    public CompletionStage<String> set(String key, String value, SetArgs options) {
        if (options == null) {
            throw new IllegalArgumentException("SET options must not be null");
        }
        return string(client.executeAsync("SET", join(key, value, options.arguments())));
    }

    public CompletionStage<Long> del(String... keys) {
        return number(client.executeAsync("DEL", keys));
    }

    public CompletionStage<Long> unlink(String... keys) {
        return number(client.executeAsync("UNLINK", keys));
    }

    public CompletionStage<Boolean> exists(String key) {
        return booleanNumber(client.executeAsync("EXISTS", key));
    }

    public CompletionStage<Long> existsCount(String... keys) {
        return number(client.executeAsync("EXISTS", keys));
    }

    public CompletionStage<String> type(String key) {
        return string(client.executeAsync("TYPE", key));
    }

    public CompletionStage<Long> expire(String key, long seconds) {
        return number(client.executeAsync("EXPIRE", key, Long.toString(seconds)));
    }

    public CompletionStage<Long> expireAt(String key, long unixSeconds) {
        return number(client.executeAsync("EXPIREAT", key, Long.toString(unixSeconds)));
    }

    public CompletionStage<Long> pexpire(String key, long milliseconds) {
        return number(client.executeAsync("PEXPIRE", key, Long.toString(milliseconds)));
    }

    public CompletionStage<Long> pexpireAt(String key, long unixMilliseconds) {
        return number(client.executeAsync("PEXPIREAT", key, Long.toString(unixMilliseconds)));
    }

    public CompletionStage<Long> persist(String key) {
        return number(client.executeAsync("PERSIST", key));
    }

    public CompletionStage<Long> ttl(String key) {
        return number(client.executeAsync("TTL", key));
    }

    public CompletionStage<Long> pttl(String key) {
        return number(client.executeAsync("PTTL", key));
    }

    public CompletionStage<String> rename(String key, String newKey) {
        return string(client.executeAsync("RENAME", key, newKey));
    }

    public CompletionStage<Boolean> renameNx(String key, String newKey) {
        return booleanNumber(client.executeAsync("RENAMENX", key, newKey));
    }

    public CompletionStage<Long> touch(String... keys) {
        return number(client.executeAsync("TOUCH", keys));
    }

    public CompletionStage<List<String>> keys(String pattern) {
        return client.executeAsync("KEYS", pattern).thenApply(BobaStrawAsyncCommands::stringList);
    }

    public CompletionStage<String> randomKey() {
        return string(client.executeAsync("RANDOMKEY"));
    }

    public CompletionStage<Long> incr(String key) {
        return number(client.executeAsync("INCR", key));
    }

    public CompletionStage<Long> incrBy(String key, long increment) {
        return number(client.executeAsync("INCRBY", key, Long.toString(increment)));
    }

    public CompletionStage<Double> incrByFloat(String key, double increment) {
        return doubleNumber(client.executeAsync("INCRBYFLOAT", key, Double.toString(increment)));
    }

    public CompletionStage<Long> decr(String key) {
        return number(client.executeAsync("DECR", key));
    }

    public CompletionStage<Long> decrBy(String key, long decrement) {
        return number(client.executeAsync("DECRBY", key, Long.toString(decrement)));
    }

    public CompletionStage<Long> append(String key, String value) {
        return number(client.executeAsync("APPEND", key, value));
    }

    public CompletionStage<Long> strlen(String key) {
        return number(client.executeAsync("STRLEN", key));
    }

    public CompletionStage<String> getSet(String key, String value) {
        return string(client.executeAsync("GETSET", key, value));
    }

    public CompletionStage<List<String>> mget(String... keys) {
        return client.executeAsync("MGET", keys).thenApply(BobaStrawAsyncCommands::stringList);
    }

    public CompletionStage<String> mset(Map<String, String> values) {
        return string(client.executeAsync("MSET", pairs(values)));
    }

    public CompletionStage<Boolean> msetNx(Map<String, String> values) {
        return booleanNumber(client.executeAsync("MSETNX", pairs(values)));
    }

    public CompletionStage<Boolean> setNx(String key, String value) {
        return booleanNumber(client.executeAsync("SETNX", key, value));
    }

    public CompletionStage<String> setEx(String key, long seconds, String value) {
        return string(client.executeAsync("SETEX", key, Long.toString(seconds), value));
    }

    public CompletionStage<String> psetEx(String key, long milliseconds, String value) {
        return string(client.executeAsync("PSETEX", key, Long.toString(milliseconds), value));
    }

    public CompletionStage<String> getRange(String key, long start, long end) {
        return string(client.executeAsync("GETRANGE", key, Long.toString(start), Long.toString(end)));
    }

    public CompletionStage<Long> setRange(String key, long offset, String value) {
        return number(client.executeAsync("SETRANGE", key, Long.toString(offset), value));
    }

    public CompletionStage<Long> getBit(String key, long offset) {
        return number(client.executeAsync("GETBIT", key, Long.toString(offset)));
    }

    public CompletionStage<Long> setBit(String key, long offset, long value) {
        if (value != 0 && value != 1) {
            throw new IllegalArgumentException("Redis bit values must be 0 or 1");
        }
        return number(client.executeAsync("SETBIT", key, Long.toString(offset), Long.toString(value)));
    }

    public CompletionStage<String> hget(String key, String field) {
        return string(client.executeAsync("HGET", key, field));
    }

    public CompletionStage<Long> hset(String key, String field, String value) {
        return number(client.executeAsync("HSET", key, field, value));
    }

    public CompletionStage<Map<String, String>> hgetall(String key) {
        return client.executeAsync("HGETALL", key).thenApply(BobaStrawAsyncCommands::stringMap);
    }

    public CompletionStage<Long> lpush(String key, String... values) {
        return number(client.executeAsync("LPUSH", prepend(key, values)));
    }

    public CompletionStage<Long> rpush(String key, String... values) {
        return number(client.executeAsync("RPUSH", prepend(key, values)));
    }

    public CompletionStage<List<String>> lrange(String key, long start, long stop) {
        return client.executeAsync("LRANGE", key, Long.toString(start), Long.toString(stop))
            .thenApply(BobaStrawAsyncCommands::stringList);
    }

    public CompletionStage<Long> sadd(String key, String... members) {
        return number(client.executeAsync("SADD", prepend(key, members)));
    }

    public CompletionStage<Set<String>> smembers(String key) {
        return client.executeAsync("SMEMBERS", key).thenApply(BobaStrawAsyncCommands::stringSet);
    }

    public CompletionStage<Long> zadd(String key, double score, String member) {
        return number(client.executeAsync("ZADD", key, Double.toString(score), member));
    }

    public CompletionStage<List<String>> zrange(String key, long start, long stop) {
        return client.executeAsync("ZRANGE", key, Long.toString(start), Long.toString(stop))
            .thenApply(BobaStrawAsyncCommands::stringList);
    }

    public CompletionStage<RespValue> eval(String script, String[] keys, String... arguments) {
        String[] command = new String[3 + keys.length + arguments.length];
        command[0] = "EVAL";
        command[1] = script;
        command[2] = Integer.toString(keys.length);
        System.arraycopy(keys, 0, command, 3, keys.length);
        System.arraycopy(arguments, 0, command, 3 + keys.length, arguments.length);
        return client.executeAsync(command[0], Arrays.copyOfRange(command, 1, command.length));
    }

    private CompletionStage<String> string(CompletionStage<RespValue> stage) {
        return stage.thenApply(RespValue::asString);
    }

    private CompletionStage<Long> number(CompletionStage<RespValue> stage) {
        return stage.thenApply(RespValue::asLong);
    }

    private CompletionStage<Boolean> booleanNumber(CompletionStage<RespValue> stage) {
        return number(stage).thenApply(value -> value.longValue() != 0L);
    }

    private CompletionStage<Double> doubleNumber(CompletionStage<RespValue> stage) {
        return stage.thenApply(BobaStrawAsyncCommands::asDouble);
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

    private static List<String> stringList(RespValue value) {
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

    private static Set<String> stringSet(RespValue value) {
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

    private static Map<String, String> stringMap(RespValue value) {
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
