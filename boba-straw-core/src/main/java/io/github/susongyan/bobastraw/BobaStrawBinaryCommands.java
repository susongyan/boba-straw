package io.github.susongyan.bobastraw;

import io.github.susongyan.bobastraw.protocol.RespValue;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletionStage;

/** Binary-safe command facade. Values are never decoded through the default charset. */
public final class BobaStrawBinaryCommands {
    private final BinaryCommandExecutor executor;

    BobaStrawBinaryCommands(BobaStrawClient client) {
        this(command -> client.executeEncodedCommand(command));
    }

    private BobaStrawBinaryCommands(BinaryCommandExecutor executor) {
        this.executor = executor;
    }

    static BobaStrawBinaryCommands withExecutor(BinaryCommandExecutor executor) {
        return new BobaStrawBinaryCommands(executor);
    }

    /** Binary-safe EVAL; accessed keys must be explicitly declared. */
    public CompletionStage<RespValue> eval(byte[] script, byte[][] keys, byte[]... arguments) {
        return eval(script, ScriptOutput.raw(), keys, arguments);
    }

    public <T> CompletionStage<T> eval(byte[] script, ScriptOutput<T> output,
                                      byte[][] keys, byte[]... arguments) {
        return executor.execute(ScriptCommandFactory.binary(false, script, output, keys, arguments));
    }

    /** Executes once by ASCII digest; NOSCRIPT does not cause loading or retry. */
    public CompletionStage<RespValue> evalSha(String sha1, byte[][] keys, byte[]... arguments) {
        return evalSha(sha1, ScriptOutput.raw(), keys, arguments);
    }

    public <T> CompletionStage<T> evalSha(String sha1, ScriptOutput<T> output,
                                         byte[][] keys, byte[]... arguments) {
        return executor.execute(ScriptCommandFactory.binary(true,
            ascii(ScriptCommandFactory.sha(sha1)), output, keys, arguments));
    }

    /** Loads the exact script bytes without executing them; returns its ASCII SHA1 digest. */
    public CompletionStage<String> scriptLoad(byte[] script) {
        return executor.execute(ScriptCommandFactory.load(script));
    }

    public CompletionStage<byte[]> get(byte[] key) {
        return typed("GET", CommandDecoders.BYTES, key);
    }

    public CompletionStage<byte[]> set(byte[] key, byte[] value) {
        return typed("SET", CommandDecoders.BYTES, key, value);
    }

    /**
     * Uses the same options as the String API. Returns OK, null, or the old bytes
     * when GET is requested. GET/EXAT/PXAT require Redis 6.2+, KEEPTTL 6.0+,
     * and NX with GET 7.0+. Unsupported options remain server errors.
     */
    public CompletionStage<byte[]> set(byte[] key, byte[] value, SetArgs options) {
        if (options == null) {
            throw new IllegalArgumentException("SET options must not be null");
        }
        String[] suffix = options.arguments();
        byte[][] arguments = new byte[suffix.length + 2][];
        arguments[0] = key;
        arguments[1] = value;
        for (int index = 0; index < suffix.length; index++) {
            arguments[index + 2] = ascii(suffix[index]);
        }
        return typed("SET", CommandDecoders.BYTES, arguments);
    }

    /** Returns one entry per key, preserving order and duplicates; missing/non-string values are null. */
    public CompletionStage<List<byte[]>> mget(byte[]... keys) {
        return typed("MGET", CommandDecoders.BYTE_LIST, keys);
    }

    /** Alternating key/value pairs, not a Map whose byte[] keys would use identity equality. */
    public CompletionStage<byte[]> mset(byte[]... keyValues) {
        requirePairs(keyValues);
        return typed("MSET", CommandDecoders.BYTES, keyValues);
    }

    /** Atomically writes all pairs only if all keys are absent; returns false without partial writes. */
    public CompletionStage<Boolean> msetNx(byte[]... keyValues) {
        requirePairs(keyValues);
        return typed("MSETNX", CommandDecoders.BOOLEAN, keyValues);
    }

    public CompletionStage<Long> append(byte[] key, byte[] value) {
        return typed("APPEND", CommandDecoders.LONG, key, value);
    }

    public CompletionStage<Long> strlen(byte[] key) {
        return typed("STRLEN", CommandDecoders.LONG, key);
    }

    /** Inclusive byte offsets; negative offsets count from the end. Missing ranges return empty bytes. */
    public CompletionStage<byte[]> getRange(byte[] key, long start, long end) {
        return typed("GETRANGE", CommandDecoders.BYTES, key, ascii(Long.toString(start)), ascii(Long.toString(end)));
    }

    /** Overwrites at a byte offset, padding with zero bytes when necessary. */
    public CompletionStage<Long> setRange(byte[] key, long offset, byte[] value) {
        if (offset < 0) {
            throw new IllegalArgumentException("SETRANGE offset must not be negative");
        }
        return typed("SETRANGE", CommandDecoders.LONG, key, ascii(Long.toString(offset)), value);
    }

    public CompletionStage<Boolean> exists(byte[] key) {
        return typed("EXISTS", CommandDecoders.BOOLEAN, key);
    }

    public CompletionStage<Long> existsCount(byte[]... keys) {
        return typed("EXISTS", CommandDecoders.LONG, keys);
    }

    public CompletionStage<Long> unlink(byte[]... keys) {
        return typed("UNLINK", CommandDecoders.LONG, keys);
    }

    public CompletionStage<String> type(byte[] key) {
        return typed("TYPE", CommandDecoders.STRING, key);
    }

    public CompletionStage<Long> expire(byte[] key, long time) {
        return typed("EXPIRE", CommandDecoders.LONG, key, ascii(Long.toString(time)));
    }

    public CompletionStage<Long> pexpire(byte[] key, long time) {
        return typed("PEXPIRE", CommandDecoders.LONG, key, ascii(Long.toString(time)));
    }

    public CompletionStage<Long> expireAt(byte[] key, long time) {
        return typed("EXPIREAT", CommandDecoders.LONG, key, ascii(Long.toString(time)));
    }

    public CompletionStage<Long> pexpireAt(byte[] key, long time) {
        return typed("PEXPIREAT", CommandDecoders.LONG, key, ascii(Long.toString(time)));
    }

    public CompletionStage<Long> ttl(byte[] key) {
        return typed("TTL", CommandDecoders.LONG, key);
    }

    public CompletionStage<Long> pttl(byte[] key) {
        return typed("PTTL", CommandDecoders.LONG, key);
    }

    public CompletionStage<Long> persist(byte[] key) {
        return typed("PERSIST", CommandDecoders.LONG, key);
    }

    public CompletionStage<Long> incr(byte[] key) {
        return typed("INCR", CommandDecoders.LONG, key);
    }

    public CompletionStage<Long> decr(byte[] key) {
        return typed("DECR", CommandDecoders.LONG, key);
    }

    public CompletionStage<Long> incrBy(byte[] key, long amount) {
        return typed("INCRBY", CommandDecoders.LONG, key, ascii(Long.toString(amount)));
    }

    public CompletionStage<Long> decrBy(byte[] key, long amount) {
        return typed("DECRBY", CommandDecoders.LONG, key, ascii(Long.toString(amount)));
    }

    public CompletionStage<Long> getBit(byte[] key, long offset) {
        return typed("GETBIT", CommandDecoders.LONG, key, ascii(Long.toString(offset)));
    }

    public CompletionStage<Long> setBit(byte[] key, long offset, long value) {
        return typed("SETBIT", CommandDecoders.LONG, key, ascii(Long.toString(offset)), ascii(Long.toString(value)));
    }

    public CompletionStage<Long> bitCount(byte[] key) {
        return typed("BITCOUNT", CommandDecoders.LONG, key);
    }

    public CompletionStage<Long> bitCount(byte[] key, long start, long end) {
        return typed("BITCOUNT", CommandDecoders.LONG, key, ascii(Long.toString(start)), ascii(Long.toString(end)));
    }

    public CompletionStage<byte[]> hget(byte[] key, byte[] field) {
        return typed("HGET", CommandDecoders.BYTES, key, field);
    }

    public CompletionStage<Long> hset(byte[] key, byte[] field, byte[] value) {
        return typed("HSET", CommandDecoders.LONG, key, field, value);
    }

    public CompletionStage<List<byte[]>> hmget(byte[] key, byte[]... fields) {
        return typed("HMGET", CommandDecoders.BYTE_LIST, prepend(key, fields));
    }

    /** Field/value entries in server reply order; array keys are not Java Map keys. No sort order is promised. */
    public CompletionStage<List<java.util.Map.Entry<byte[], byte[]>>> hgetall(byte[] key) {
        return typed("HGETALL", CommandDecoders.BYTE_ENTRIES, key);
    }

    public CompletionStage<Long> hdel(byte[] key, byte[]... fields) {
        return typed("HDEL", CommandDecoders.LONG, prepend(key, fields));
    }

    public CompletionStage<Boolean> hexists(byte[] key, byte[] field) {
        return typed("HEXISTS", CommandDecoders.BOOLEAN, key, field);
    }

    public CompletionStage<Long> hlen(byte[] key) {
        return typed("HLEN", CommandDecoders.LONG, key);
    }

    public CompletionStage<Long> hincrBy(byte[] key, byte[] field, long amount) {
        return typed("HINCRBY", CommandDecoders.LONG, key, field, ascii(Long.toString(amount)));
    }

    public CompletionStage<Long> lpush(byte[] key, byte[]... values) {
        return typed("LPUSH", CommandDecoders.LONG, prepend(key, values));
    }

    public CompletionStage<Long> rpush(byte[] key, byte[]... values) {
        return typed("RPUSH", CommandDecoders.LONG, prepend(key, values));
    }

    public CompletionStage<byte[]> lpop(byte[] key) {
        return typed("LPOP", CommandDecoders.BYTES, key);
    }

    public CompletionStage<byte[]> rpop(byte[] key) {
        return typed("RPOP", CommandDecoders.BYTES, key);
    }

    public CompletionStage<List<byte[]>> lrange(byte[] key, long start, long stop) {
        return typed("LRANGE", CommandDecoders.BYTE_LIST, key, ascii(Long.toString(start)), ascii(Long.toString(stop)));
    }

    public CompletionStage<Long> llen(byte[] key) {
        return typed("LLEN", CommandDecoders.LONG, key);
    }

    public CompletionStage<Long> sadd(byte[] key, byte[]... members) {
        return typed("SADD", CommandDecoders.LONG, prepend(key, members));
    }

    public CompletionStage<Long> srem(byte[] key, byte[]... members) {
        return typed("SREM", CommandDecoders.LONG, prepend(key, members));
    }

    /** Redis deduplicates by byte content. A List avoids Java byte[] identity-based Set semantics. */
    public CompletionStage<List<byte[]>> smembers(byte[] key) {
        return typed("SMEMBERS", CommandDecoders.BYTE_LIST, key);
    }

    public CompletionStage<Long> scard(byte[] key) {
        return typed("SCARD", CommandDecoders.LONG, key);
    }

    public CompletionStage<Boolean> sismember(byte[] key, byte[] member) {
        return typed("SISMEMBER", CommandDecoders.BOOLEAN, key, member);
    }

    public CompletionStage<Long> zadd(byte[] key, double score, byte[] member) {
        return typed("ZADD", CommandDecoders.LONG, key, ascii(Double.toString(score)), member);
    }

    public CompletionStage<Long> zrem(byte[] key, byte[]... members) {
        return typed("ZREM", CommandDecoders.LONG, prepend(key, members));
    }

    public CompletionStage<List<byte[]>> zrange(byte[] key, long start, long stop) {
        return typed("ZRANGE", CommandDecoders.BYTE_LIST, key, ascii(Long.toString(start)), ascii(Long.toString(stop)));
    }

    public CompletionStage<Double> zscore(byte[] key, byte[] member) {
        return typed("ZSCORE", CommandDecoders.NULLABLE_DOUBLE, key, member);
    }

    public CompletionStage<Long> zcard(byte[] key) {
        return typed("ZCARD", CommandDecoders.LONG, key);
    }

    public CompletionStage<Long> zrank(byte[] key, byte[] member) {
        return typed("ZRANK", CommandDecoders.NULLABLE_LONG, key, member);
    }

    private <T> CompletionStage<T> typed(String command, CommandDecoder<T> decoder, byte[]... arguments) {
        if (arguments == null || arguments.length == 0) {
            throw new IllegalArgumentException(command + " requires arguments");
        }
        return executor.execute(TypedCommand.binary(command, decoder, arguments));
    }

    private static byte[][] prepend(byte[] key, byte[][] values) {
        if (values == null || values.length == 0) {
            throw new IllegalArgumentException("At least one field, value or member is required");
        }
        byte[][] result = new byte[values.length + 1][];
        result[0] = key;
        System.arraycopy(values, 0, result, 1, values.length);
        return result;
    }

    private static void requirePairs(byte[][] keyValues) {
        if (keyValues == null || keyValues.length == 0 || keyValues.length % 2 != 0) {
            throw new IllegalArgumentException("At least one complete key/value pair is required");
        }
    }

    private static byte[] ascii(String value) {
        return value.getBytes(StandardCharsets.US_ASCII);
    }

    public CompletionStage<Long> del(byte[]... keys) {
        return typed("DEL", CommandDecoders.LONG, keys);
    }
}
