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

    public Long del(String... keys) {
        return client.await(client.async().del(keys));
    }

    public boolean exists(String key) {
        return client.await(client.async().exists(key));
    }

    public Long expire(String key, long seconds) {
        return client.await(client.async().expire(key, seconds));
    }

    public Long ttl(String key) {
        return client.await(client.async().ttl(key));
    }

    public Long incr(String key) {
        return client.await(client.async().incr(key));
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
