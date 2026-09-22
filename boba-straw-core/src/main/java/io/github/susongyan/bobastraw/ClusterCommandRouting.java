package io.github.susongyan.bobastraw;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Conservative key metadata for the currently supported ordinary command surface. */
final class ClusterCommandRouting {
    private static final Set<String> FIRST_KEY = words(
        "GET SET SETNX SETEX PSETEX GETSET APPEND STRLEN GETRANGE SETRANGE GETBIT SETBIT "
        + "BITCOUNT BITPOS BITFIELD INCR INCRBY INCRBYFLOAT DECR DECRBY TYPE EXPIRE EXPIREAT "
        + "PEXPIRE PEXPIREAT PERSIST TTL PTTL DUMP HGET HSET HGETALL HDEL HEXISTS HLEN HKEYS "
        + "HVALS HMGET HMSET HSETNX HINCRBY HINCRBYFLOAT HSCAN LPUSH RPUSH LPUSHX RPUSHX "
        + "LPOP RPOP LRANGE LLEN LINDEX LSET LREM LTRIM LINSERT SADD SREM SMEMBERS SCARD "
        + "SISMEMBER SPOP SRANDMEMBER SSCAN ZADD ZREM ZRANGE ZREVRANGE ZCARD ZSCORE ZRANK "
        + "ZREVRANK ZINCRBY ZCOUNT ZRANGEBYSCORE ZREVRANGEBYSCORE ZREMRANGEBYRANK "
        + "ZREMRANGEBYSCORE ZSCAN PFADD GEOADD GEOPOS GEODIST GEOHASH"
    );
    private static final Set<String> ALL_KEYS = words(
        "DEL UNLINK EXISTS TOUCH MGET SDIFF SINTER SUNION SDIFFSTORE SINTERSTORE SUNIONSTORE "
        + "PFCOUNT PFMERGE"
    );
    private static final Set<String> NO_KEYS = words("PING ECHO INFO TIME DBSIZE LASTSAVE RANDOMKEY KEYS SCAN");
    private static final Set<String> CLUSTER_READS = words("SLOTS NODES INFO KEYSLOT");
    private static final Set<String> STATEFUL = words(
        "MULTI EXEC DISCARD WATCH UNWATCH SELECT AUTH HELLO CLIENT QUIT RESET READONLY READWRITE "
        + "ASKING SUBSCRIBE PSUBSCRIBE SSUBSCRIBE UNSUBSCRIBE PUNSUBSCRIBE SUNSUBSCRIBE MONITOR "
        + "SYNC PSYNC BLPOP BRPOP BRPOPLPUSH BLMOVE BLMPOP BZPOPMIN BZPOPMAX BZMPOP WAIT WAITAOF"
    );

    private ClusterCommandRouting() {
    }

    static void validate(String command, String[] arguments) {
        if (command == null || command.isEmpty() || arguments == null) {
            throw new IllegalArgumentException("Command and arguments are required");
        }
        for (String argument : arguments) {
            if (argument == null) {
                throw new IllegalArgumentException("Command arguments must not be null");
            }
        }
        String name = command.toUpperCase(Locale.ROOT);
        if (STATEFUL.contains(name) || "XREAD".equals(name) || "XREADGROUP".equals(name)) {
            throw new IllegalArgumentException("Command requires a dedicated Cluster API: " + command);
        }
    }

    static Integer slot(String command, String[] arguments) {
        validate(command, arguments);
        String name = command.toUpperCase(Locale.ROOT);
        if (FIRST_KEY.contains(name)) {
            require(arguments.length >= 1, "Command requires a key");
            return ClusterSlot.of(arguments[0]);
        }
        if (ALL_KEYS.contains(name)) {
            require(arguments.length >= 1, "Command requires keys");
            return sameSlot(arguments);
        }
        if ("MSET".equals(name) || "MSETNX".equals(name)) {
            require(arguments.length > 0 && arguments.length % 2 == 0, "Expected key/value pairs");
            String[] keys = new String[arguments.length / 2];
            for (int i = 0; i < keys.length; i++) {
                keys[i] = arguments[i * 2];
            }
            return sameSlot(keys);
        }
        if ("RENAME".equals(name) || "RENAMENX".equals(name) || "RPOPLPUSH".equals(name)
            || "SMOVE".equals(name)) {
            require(arguments.length >= 2, "Command requires source and destination keys");
            return sameSlot(new String[] {arguments[0], arguments[1]});
        }
        if ("EVAL".equals(name) || "EVALSHA".equals(name)) {
            require(arguments.length >= 2, "Script command requires numkeys");
            int count;
            try {
                count = Integer.parseInt(arguments[1]);
            } catch (NumberFormatException error) {
                throw new IllegalArgumentException("Invalid script numkeys", error);
            }
            require(count >= 0 && count <= arguments.length - 2, "Invalid script key count");
            return sameSlot(Arrays.copyOfRange(arguments, 2, 2 + count));
        }
        if (NO_KEYS.contains(name)) {
            return null;
        }
        if ("CLUSTER".equals(name) && arguments.length > 0
            && CLUSTER_READS.contains(arguments[0].toUpperCase(Locale.ROOT))) {
            return null;
        }
        throw new UnknownCommandException(
            "Unknown Cluster key metadata; use executeWithKeysAsync with all keys: " + command
        );
    }

    static Integer explicitSlot(String[] keys, String command, String[] arguments) {
        Integer declared = sameSlot(keys);
        try {
            Integer actual = slot(command, arguments);
            if (actual == null ? declared != null : !actual.equals(declared)) {
                throw new IllegalArgumentException("Explicit keys disagree with known command routing");
            }
        } catch (UnknownCommandException allowed) {
            // Unknown ordinary commands rely on the caller's complete key declaration.
        }
        return declared;
    }

    private static final class UnknownCommandException extends IllegalArgumentException {
        private UnknownCommandException(String message) {
            super(message);
        }
    }

    static Integer sameSlot(String[] keys) {
        if (keys == null) {
            throw new IllegalArgumentException("Explicit keys must not be null");
        }
        Integer slot = null;
        for (String key : keys) {
            if (key == null) {
                throw new IllegalArgumentException("Keys must not be null");
            }
            int next = ClusterSlot.of(key);
            if (slot != null && slot.intValue() != next) {
                throw new IllegalArgumentException("CROSSSLOT: all command keys must hash to the same slot");
            }
            slot = next;
        }
        return slot;
    }

    private static Set<String> words(String values) {
        return new HashSet<String>(Arrays.asList(values.split(" ")));
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
