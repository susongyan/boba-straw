package io.github.susongyan.bobastraw;

import io.github.susongyan.bobastraw.protocol.RespValue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionStage;

/** Blocking facade over transport completions from Boba Straw's asynchronous NIO core. */
public final class BobaStrawSyncCommands {
    private final BobaStrawClient client;

    BobaStrawSyncCommands(BobaStrawClient client) {
        this.client = client;
    }

    public String ping() {
        return string("PING");
    }

    public String get(String key) {
        return string("GET", key);
    }

    public String set(String key, String value) {
        return string("SET", key, value);
    }

    public String set(String key, String value, SetArgs options) {
        if (options == null) {
            throw new IllegalArgumentException("SET options must not be null");
        }
        return string("SET", join(key, value, options.arguments()));
    }

    public Long del(String... keys) {
        return number("DEL", keys);
    }

    public Long unlink(String... keys) {
        return number("UNLINK", keys);
    }

    public boolean exists(String key) {
        return booleanNumber("EXISTS", key);
    }

    public Long existsCount(String... keys) {
        return number("EXISTS", keys);
    }

    public String type(String key) {
        return string("TYPE", key);
    }

    public Long expire(String key, long seconds) {
        return number("EXPIRE", key, Long.toString(seconds));
    }

    public Long expireAt(String key, long unixSeconds) {
        return number("EXPIREAT", key, Long.toString(unixSeconds));
    }

    public Long pexpire(String key, long milliseconds) {
        return number("PEXPIRE", key, Long.toString(milliseconds));
    }

    public Long pexpireAt(String key, long unixMilliseconds) {
        return number("PEXPIREAT", key, Long.toString(unixMilliseconds));
    }

    public Long persist(String key) {
        return number("PERSIST", key);
    }

    public Long ttl(String key) {
        return number("TTL", key);
    }

    public Long pttl(String key) {
        return number("PTTL", key);
    }

    public String rename(String key, String newKey) {
        return string("RENAME", key, newKey);
    }

    public boolean renameNx(String key, String newKey) {
        return booleanNumber("RENAMENX", key, newKey);
    }

    public Long touch(String... keys) {
        return number("TOUCH", keys);
    }

    public List<String> keys(String pattern) {
        return BobaStrawAsyncCommands.stringList(response("KEYS", pattern));
    }

    public String randomKey() {
        return string("RANDOMKEY");
    }

    public Long incr(String key) {
        return number("INCR", key);
    }

    public Long incrBy(String key, long increment) {
        return number("INCRBY", key, Long.toString(increment));
    }

    public Double incrByFloat(String key, double increment) {
        return asDouble(response("INCRBYFLOAT", key, Double.toString(increment)));
    }

    public Long decr(String key) {
        return number("DECR", key);
    }

    public Long decrBy(String key, long decrement) {
        return number("DECRBY", key, Long.toString(decrement));
    }

    public Long append(String key, String value) {
        return number("APPEND", key, value);
    }

    public Long strlen(String key) {
        return number("STRLEN", key);
    }

    public String getSet(String key, String value) {
        return string("GETSET", key, value);
    }

    public List<String> mget(String... keys) {
        return BobaStrawAsyncCommands.stringList(response("MGET", keys));
    }

    public String mset(Map<String, String> values) {
        return string("MSET", pairs(values));
    }

    public boolean msetNx(Map<String, String> values) {
        return booleanNumber("MSETNX", pairs(values));
    }

    public boolean setNx(String key, String value) {
        return booleanNumber("SETNX", key, value);
    }

    public String setEx(String key, long seconds, String value) {
        return string("SETEX", key, Long.toString(seconds), value);
    }

    public String psetEx(String key, long milliseconds, String value) {
        return string("PSETEX", key, Long.toString(milliseconds), value);
    }

    public String getRange(String key, long start, long end) {
        return string("GETRANGE", key, Long.toString(start), Long.toString(end));
    }

    public Long setRange(String key, long offset, String value) {
        return number("SETRANGE", key, Long.toString(offset), value);
    }

    public Long getBit(String key, long offset) {
        return number("GETBIT", key, Long.toString(offset));
    }

    public Long setBit(String key, long offset, long value) {
        if (value != 0 && value != 1) {
            throw new IllegalArgumentException("Redis bit values must be 0 or 1");
        }
        return number("SETBIT", key, Long.toString(offset), Long.toString(value));
    }

    public String hget(String key, String field) {
        return string("HGET", key, field);
    }

    public Long hset(String key, String field, String value) {
        return number("HSET", key, field, value);
    }

    public Map<String, String> hgetall(String key) {
        return BobaStrawAsyncCommands.stringMap(response("HGETALL", key));
    }

    public Long lpush(String key, String... values) {
        return number("LPUSH", prepend(key, values));
    }

    public Long rpush(String key, String... values) {
        return number("RPUSH", prepend(key, values));
    }

    public List<String> lrange(String key, long start, long stop) {
        return BobaStrawAsyncCommands.stringList(
            response("LRANGE", key, Long.toString(start), Long.toString(stop))
        );
    }

    public Long sadd(String key, String... members) {
        return number("SADD", prepend(key, members));
    }

    public Set<String> smembers(String key) {
        return BobaStrawAsyncCommands.stringSet(response("SMEMBERS", key));
    }

    public Long zadd(String key, double score, String member) {
        return number("ZADD", key, Double.toString(score), member);
    }

    public List<String> zrange(String key, long start, long stop) {
        return BobaStrawAsyncCommands.stringList(
            response("ZRANGE", key, Long.toString(start), Long.toString(stop))
        );
    }

    public RespValue eval(String script, String[] keys, String... arguments) {
        String[] command = new String[3 + keys.length + arguments.length];
        command[0] = "EVAL";
        command[1] = script;
        command[2] = Integer.toString(keys.length);
        System.arraycopy(keys, 0, command, 3, keys.length);
        System.arraycopy(arguments, 0, command, 3 + keys.length, arguments.length);
        String[] tail = new String[command.length - 1];
        System.arraycopy(command, 1, tail, 0, tail.length);
        return response("EVAL", tail);
    }

    private String string(String command, String... arguments) {
        return response(command, arguments).asString();
    }

    private Long number(String command, String... arguments) {
        return response(command, arguments).asLong();
    }

    private boolean booleanNumber(String command, String... arguments) {
        return response(command, arguments).asLong() != 0L;
    }

    private RespValue response(String command, String... arguments) {
        return client.await(command(command, arguments));
    }

    private CompletionStage<RespValue> command(String command, String... arguments) {
        return client.executeTransport(command, arguments);
    }

    private static double asDouble(RespValue value) {
        if (value instanceof RespValue.DoubleValue) {
            return ((RespValue.DoubleValue) value).value;
        }
        return Double.parseDouble(value.asString());
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
}
