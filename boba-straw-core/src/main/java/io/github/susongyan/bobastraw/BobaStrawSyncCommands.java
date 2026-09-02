package io.github.susongyan.bobastraw;

import java.util.List;
import java.util.Map;
import java.util.Set;
import io.github.susongyan.bobastraw.protocol.RespValue;

/** Blocking facade over Boba Straw's asynchronous NIO core. */
public final class BobaStrawSyncCommands {
    private final BobaStrawClient client;

    BobaStrawSyncCommands(BobaStrawClient client) {
        this.client = client;
    }

    public String ping() {
        return client.await(client.async().ping());
    }

    public String get(String key) {
        return client.await(client.async().get(key));
    }

    public String set(String key, String value) {
        return client.await(client.async().set(key, value));
    }

    public String set(String key, String value, SetArgs options) {
        return client.await(client.async().set(key, value, options));
    }

    public Long del(String... keys) {
        return client.await(client.async().del(keys));
    }

    public Long unlink(String... keys) {
        return client.await(client.async().unlink(keys));
    }

    public boolean exists(String key) {
        return client.await(client.async().exists(key));
    }

    public Long existsCount(String... keys) {
        return client.await(client.async().existsCount(keys));
    }

    public String type(String key) {
        return client.await(client.async().type(key));
    }

    public Long expire(String key, long seconds) {
        return client.await(client.async().expire(key, seconds));
    }

    public Long expireAt(String key, long unixSeconds) {
        return client.await(client.async().expireAt(key, unixSeconds));
    }

    public Long pexpire(String key, long milliseconds) {
        return client.await(client.async().pexpire(key, milliseconds));
    }

    public Long pexpireAt(String key, long unixMilliseconds) {
        return client.await(client.async().pexpireAt(key, unixMilliseconds));
    }

    public Long persist(String key) {
        return client.await(client.async().persist(key));
    }

    public Long ttl(String key) {
        return client.await(client.async().ttl(key));
    }

    public Long pttl(String key) {
        return client.await(client.async().pttl(key));
    }

    public String rename(String key, String newKey) {
        return client.await(client.async().rename(key, newKey));
    }

    public boolean renameNx(String key, String newKey) {
        return client.await(client.async().renameNx(key, newKey));
    }

    public Long touch(String... keys) {
        return client.await(client.async().touch(keys));
    }

    public List<String> keys(String pattern) {
        return client.await(client.async().keys(pattern));
    }

    public String randomKey() {
        return client.await(client.async().randomKey());
    }

    public Long incr(String key) {
        return client.await(client.async().incr(key));
    }

    public Long incrBy(String key, long increment) {
        return client.await(client.async().incrBy(key, increment));
    }

    public Double incrByFloat(String key, double increment) {
        return client.await(client.async().incrByFloat(key, increment));
    }

    public Long decr(String key) {
        return client.await(client.async().decr(key));
    }

    public Long decrBy(String key, long decrement) {
        return client.await(client.async().decrBy(key, decrement));
    }

    public Long append(String key, String value) {
        return client.await(client.async().append(key, value));
    }

    public Long strlen(String key) {
        return client.await(client.async().strlen(key));
    }

    public String getSet(String key, String value) {
        return client.await(client.async().getSet(key, value));
    }

    public List<String> mget(String... keys) {
        return client.await(client.async().mget(keys));
    }

    public String mset(Map<String, String> values) {
        return client.await(client.async().mset(values));
    }

    public boolean msetNx(Map<String, String> values) {
        return client.await(client.async().msetNx(values));
    }

    public boolean setNx(String key, String value) {
        return client.await(client.async().setNx(key, value));
    }

    public String setEx(String key, long seconds, String value) {
        return client.await(client.async().setEx(key, seconds, value));
    }

    public String psetEx(String key, long milliseconds, String value) {
        return client.await(client.async().psetEx(key, milliseconds, value));
    }

    public String getRange(String key, long start, long end) {
        return client.await(client.async().getRange(key, start, end));
    }

    public Long setRange(String key, long offset, String value) {
        return client.await(client.async().setRange(key, offset, value));
    }

    public Long getBit(String key, long offset) {
        return client.await(client.async().getBit(key, offset));
    }

    public Long setBit(String key, long offset, long value) {
        return client.await(client.async().setBit(key, offset, value));
    }

    public String hget(String key, String field) {
        return client.await(client.async().hget(key, field));
    }

    public Long hset(String key, String field, String value) {
        return client.await(client.async().hset(key, field, value));
    }

    public Map<String, String> hgetall(String key) {
        return client.await(client.async().hgetall(key));
    }

    public Long lpush(String key, String... values) {
        return client.await(client.async().lpush(key, values));
    }

    public Long rpush(String key, String... values) {
        return client.await(client.async().rpush(key, values));
    }

    public List<String> lrange(String key, long start, long stop) {
        return client.await(client.async().lrange(key, start, stop));
    }

    public Long sadd(String key, String... members) {
        return client.await(client.async().sadd(key, members));
    }

    public Set<String> smembers(String key) {
        return client.await(client.async().smembers(key));
    }

    public Long zadd(String key, double score, String member) {
        return client.await(client.async().zadd(key, score, member));
    }

    public List<String> zrange(String key, long start, long stop) {
        return client.await(client.async().zrange(key, start, stop));
    }

    public RespValue eval(String script, String[] keys, String... arguments) {
        return client.await(client.async().eval(script, keys, arguments));
    }
}
