package io.github.susongyan.bobastraw;

import io.github.susongyan.bobastraw.protocol.RespValue;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Blocking facade over transport completions from Boba Straw's asynchronous NIO core. */
public final class BobaStrawSyncCommands {
    private final BobaStrawClient client;

    BobaStrawSyncCommands(BobaStrawClient client) {
        this.client = client;
    }

    public Long bitCount(String key) {
        return typed("BITCOUNT", CommandDecoders.LONG, key);
    }

    public Long bitCount(String key, long start, long end) {
        return typed("BITCOUNT", CommandDecoders.LONG, key, Long.toString(start), Long.toString(end));
    }

    public List<String> hmget(String key, String... fields) {
        return typed("HMGET", BobaStrawAsyncCommands::stringList, prepend(key, fields));
    }

    public Long hdel(String key, String... fields) {
        return typed("HDEL", CommandDecoders.LONG, prepend(key, fields));
    }

    public Boolean hexists(String key, String field) {
        return typed("HEXISTS", CommandDecoders.BOOLEAN, key, field);
    }

    public Long hlen(String key) {
        return typed("HLEN", CommandDecoders.LONG, key);
    }

    public Long hincrBy(String key, String field, long amount) {
        return typed("HINCRBY", CommandDecoders.LONG, key, field, Long.toString(amount));
    }

    public String lpop(String key) {
        return typed("LPOP", CommandDecoders.STRING, key);
    }

    public String rpop(String key) {
        return typed("RPOP", CommandDecoders.STRING, key);
    }

    public Long llen(String key) {
        return typed("LLEN", CommandDecoders.LONG, key);
    }

    public Long srem(String key, String... members) {
        return typed("SREM", CommandDecoders.LONG, prepend(key, members));
    }

    public Long scard(String key) {
        return typed("SCARD", CommandDecoders.LONG, key);
    }

    public Boolean sismember(String key, String member) {
        return typed("SISMEMBER", CommandDecoders.BOOLEAN, key, member);
    }

    public Long zrem(String key, String... members) {
        return typed("ZREM", CommandDecoders.LONG, prepend(key, members));
    }

    public Double zscore(String key, String member) {
        return typed("ZSCORE", CommandDecoders.NULLABLE_DOUBLE, key, member);
    }

    public Long zcard(String key) {
        return typed("ZCARD", CommandDecoders.LONG, key);
    }

    public Long zrank(String key, String member) {
        return typed("ZRANK", CommandDecoders.NULLABLE_LONG, key, member);
    }

    private <T> T typed(String command, CommandDecoder<T> decoder, String... arguments) {
        TypedCommand<T> invocation = new TypedCommand<T>(command, decoder, arguments);
        // Decode on the waiting caller, never on the EventLoop or a callback worker.
        RespValue response = client.await(client.executeTransport(invocation.name(), invocation.arguments()));
        return invocation.decoder().apply(response);
    }

    public String ping() {
        return string("PING");
    }

    /** Dedicated blocking pop; client commandTimeout applies even when timeoutSeconds is zero. */
    public List<String> blpop(long timeoutSeconds, String... keys) {
        return BobaStrawAsyncCommands.stringList(client.await(client.executeBlocking(
            BobaStrawAsyncCommands.blockingPopArguments("BLPOP", timeoutSeconds, keys), true
        )));
    }

    /** Dedicated blocking pop from the end of a list. */
    public List<String> brpop(long timeoutSeconds, String... keys) {
        return BobaStrawAsyncCommands.stringList(client.await(client.executeBlocking(
            BobaStrawAsyncCommands.blockingPopArguments("BRPOP", timeoutSeconds, keys), true
        )));
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
        return typed("KEYS", BobaStrawAsyncCommands::stringList, pattern);
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
        return typed("INCRBYFLOAT", BobaStrawSyncCommands::asDouble, key, Double.toString(increment));
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
        return typed("MGET", BobaStrawAsyncCommands::stringList, keys);
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
        return typed("HGETALL", BobaStrawAsyncCommands::stringMap, key);
    }

    public Long lpush(String key, String... values) {
        return number("LPUSH", prepend(key, values));
    }

    public Long rpush(String key, String... values) {
        return number("RPUSH", prepend(key, values));
    }

    public List<String> lrange(String key, long start, long stop) {
        return typed("LRANGE", BobaStrawAsyncCommands::stringList, key, Long.toString(start), Long.toString(stop));
    }

    public Long sadd(String key, String... members) {
        return number("SADD", prepend(key, members));
    }

    public Set<String> smembers(String key) {
        return typed("SMEMBERS", BobaStrawAsyncCommands::stringSet, key);
    }

    public Long zadd(String key, double score, String member) {
        return number("ZADD", key, Double.toString(score), member);
    }

    public List<String> zrange(String key, long start, long stop) {
        return typed("ZRANGE", BobaStrawAsyncCommands::stringList, key, Long.toString(start), Long.toString(stop));
    }

    public RespValue eval(String script, String[] keys, String... arguments) {
        return eval(script, ScriptOutput.raw(), keys, arguments);
    }

    /** Executes once; decodes on the calling thread, not on a callback worker. */
    public <T> T eval(String script, ScriptOutput<T> output, String[] keys, String... arguments) {
        return executeScript(ScriptCommandFactory.text(false, script, output, keys, arguments));
    }

    /** NOSCRIPT is returned as a server exception, without loading or retrying. */
    public RespValue evalSha(String sha1, String[] keys, String... arguments) {
        return evalSha(sha1, ScriptOutput.raw(), keys, arguments);
    }

    public <T> T evalSha(String sha1, ScriptOutput<T> output, String[] keys, String... arguments) {
        return executeScript(ScriptCommandFactory.text(true, sha1, output, keys, arguments));
    }

    /** Loads on the configured server without executing the script. */
    public String scriptLoad(String script) {
        return executeScript(ScriptCommandFactory.load(script));
    }

    private <T> T executeScript(TypedCommand<T> command) {
        return command.decoder().apply(client.await(client.executeTransport(command.name(), command.arguments())));
    }

    private String string(String command, String... arguments) {
        return typed(command, CommandDecoders.STRING, arguments);
    }

    private Long number(String command, String... arguments) {
        return typed(command, CommandDecoders.LONG, arguments);
    }

    private boolean booleanNumber(String command, String... arguments) {
        return typed(command, CommandDecoders.BOOLEAN, arguments);
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
