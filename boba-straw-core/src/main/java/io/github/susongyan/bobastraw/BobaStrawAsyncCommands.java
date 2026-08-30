package io.github.susongyan.bobastraw;

import io.github.susongyan.bobastraw.protocol.RespValue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Arrays;
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

    public CompletionStage<Long> del(String... keys) {
        return number(client.executeAsync("DEL", keys));
    }

    public CompletionStage<Boolean> exists(String key) {
        return number(client.executeAsync("EXISTS", key)).thenApply(value -> value > 0);
    }

    public CompletionStage<Long> expire(String key, long seconds) {
        return number(client.executeAsync("EXPIRE", key, Long.toString(seconds)));
    }

    public CompletionStage<Long> ttl(String key) {
        return number(client.executeAsync("TTL", key));
    }

    public CompletionStage<Long> incr(String key) {
        return number(client.executeAsync("INCR", key));
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

    private static String[] prepend(String first, String[] values) {
        String[] command = new String[values.length + 1];
        command[0] = first;
        System.arraycopy(values, 0, command, 1, values.length);
        return command;
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
