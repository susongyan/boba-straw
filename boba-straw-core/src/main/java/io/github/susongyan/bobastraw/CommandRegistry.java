package io.github.susongyan.bobastraw;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** One catalog consumed by ordinary entry points, transactions and Cluster routing. */
final class CommandRegistry {
    private static final Map<String, CommandSpec> SPECS;
    private static final CommandSpec SCRIPT_LOAD = new CommandSpec("SCRIPT LOAD", CommandSpec.Keys.NONE,
        CommandSpec.Connection.ORDINARY, CommandSpec.Access.UNKNOWN, "2.6.0");
    private static final CommandSpec SCRIPT_DEBUG = new CommandSpec("SCRIPT DEBUG", CommandSpec.Keys.UNKNOWN,
        CommandSpec.Connection.STATEFUL, CommandSpec.Access.UNKNOWN, "3.2.0");

    static {
        Map<String, CommandSpec> specs = new LinkedHashMap<String, CommandSpec>();
        register(specs, CommandSpec.Keys.FIRST, CommandSpec.Connection.ORDINARY,
            "GET SET SETNX SETEX PSETEX GETSET APPEND STRLEN GETRANGE SETRANGE GETBIT SETBIT "
            + "BITCOUNT BITPOS BITFIELD INCR INCRBY INCRBYFLOAT DECR DECRBY TYPE EXPIRE EXPIREAT "
            + "PEXPIRE PEXPIREAT PERSIST TTL PTTL DUMP HGET HSET HGETALL HDEL HEXISTS HLEN HKEYS "
            + "HVALS HMGET HMSET HSETNX HINCRBY HINCRBYFLOAT HSCAN LPUSH RPUSH LPUSHX RPUSHX "
            + "LPOP RPOP LRANGE LLEN LINDEX LSET LREM LTRIM LINSERT SADD SREM SMEMBERS SCARD "
            + "SISMEMBER SPOP SRANDMEMBER SSCAN ZADD ZREM ZRANGE ZREVRANGE ZCARD ZSCORE ZRANK "
            + "ZREVRANK ZINCRBY ZCOUNT ZRANGEBYSCORE ZREVRANGEBYSCORE ZREMRANGEBYRANK "
            + "ZREMRANGEBYSCORE ZSCAN PFADD GEOADD GEOPOS GEODIST GEOHASH");
        register(specs, CommandSpec.Keys.ALL, CommandSpec.Connection.ORDINARY,
            "DEL UNLINK EXISTS TOUCH MGET SDIFF SINTER SUNION SDIFFSTORE SINTERSTORE SUNIONSTORE PFCOUNT PFMERGE");
        register(specs, CommandSpec.Keys.NONE, CommandSpec.Connection.ORDINARY,
            "PING ECHO INFO TIME DBSIZE LASTSAVE RANDOMKEY KEYS SCAN");
        register(specs, CommandSpec.Keys.PAIRS, CommandSpec.Connection.ORDINARY, "MSET MSETNX");
        register(specs, CommandSpec.Keys.FIRST_TWO, CommandSpec.Connection.ORDINARY, "RENAME RENAMENX RPOPLPUSH SMOVE");
        register(specs, CommandSpec.Keys.SCRIPT, CommandSpec.Connection.ORDINARY, "EVAL EVALSHA");
        describe(specs, CommandSpec.Access.UNKNOWN, "2.6.0", "EVAL EVALSHA");
        register(specs, CommandSpec.Keys.UNKNOWN, CommandSpec.Connection.STATEFUL,
            "MULTI EXEC DISCARD WATCH UNWATCH SELECT AUTH HELLO CLIENT QUIT RESET READONLY READWRITE "
            + "ASKING MONITOR SYNC PSYNC");
        register(specs, CommandSpec.Keys.UNKNOWN, CommandSpec.Connection.PUBSUB,
            "SUBSCRIBE PSUBSCRIBE SSUBSCRIBE UNSUBSCRIBE PUNSUBSCRIBE SUNSUBSCRIBE");
        register(specs, CommandSpec.Keys.UNKNOWN, CommandSpec.Connection.BLOCKING,
            "BLPOP BRPOP BRPOPLPUSH BLMOVE BLMPOP BZPOPMIN BZPOPMAX BZMPOP WAIT WAITAOF XREAD XREADGROUP");
        describe(specs, CommandSpec.Access.READ_ONLY, "1.0.0", "GET MGET EXISTS TYPE TTL");
        describe(specs, CommandSpec.Access.WRITE, "1.0.0", "SET DEL EXPIRE INCR INCRBY DECR DECRBY");
        describe(specs, CommandSpec.Access.WRITE, "1.0.1", "MSET MSETNX");
        describe(specs, CommandSpec.Access.WRITE, "1.2.0", "EXPIREAT");
        describe(specs, CommandSpec.Access.WRITE, "2.2.0", "PERSIST SETBIT SETRANGE");
        describe(specs, CommandSpec.Access.READ_ONLY, "2.2.0", "GETBIT STRLEN");
        describe(specs, CommandSpec.Access.READ_ONLY, "2.4.0", "GETRANGE");
        describe(specs, CommandSpec.Access.WRITE, "2.0.0", "APPEND");
        describe(specs, CommandSpec.Access.WRITE, "2.6.0", "PEXPIRE PEXPIREAT");
        describe(specs, CommandSpec.Access.READ_ONLY, "2.6.0", "PTTL BITCOUNT");
        describe(specs, CommandSpec.Access.READ_ONLY, "2.8.0", "SCAN HSCAN SSCAN ZSCAN");
        describe(specs, CommandSpec.Access.WRITE, "4.0.0", "UNLINK");
        describe(specs, CommandSpec.Access.READ_ONLY, "2.0.0", "HGET HMGET HGETALL HEXISTS HLEN ZRANK");
        describe(specs, CommandSpec.Access.WRITE, "2.0.0", "HSET HDEL HINCRBY");
        describe(specs, CommandSpec.Access.READ_ONLY, "1.0.0", "LRANGE LLEN SMEMBERS SCARD SISMEMBER");
        describe(specs, CommandSpec.Access.WRITE, "1.0.0", "LPUSH RPUSH LPOP RPOP SADD SREM");
        describe(specs, CommandSpec.Access.READ_ONLY, "1.2.0", "ZRANGE ZSCORE ZCARD");
        describe(specs, CommandSpec.Access.WRITE, "1.2.0", "ZADD ZREM");
        SPECS = Collections.unmodifiableMap(specs);
    }

    private CommandRegistry() {
    }

    static CommandSpec lookup(String command) {
        return SPECS.get(CommandArgs.commandName(command));
    }

    static java.util.Collection<CommandSpec> all() {
        return SPECS.values();
    }

    /** Resolves only known forms; an unknown SCRIPT subcommand retains the Raw escape-hatch policy. */
    static CommandSpec resolve(String command, CommandArgs args) {
        if ("SCRIPT".equals(CommandArgs.commandName(command)) && args.size() > 0) {
            String subcommand = CommandArgs.commandName(args.control(0));
            if ("LOAD".equals(subcommand)) {
                if (args.size() != 2) {
                    throw new IllegalArgumentException("SCRIPT LOAD requires exactly one script");
                }
                return SCRIPT_LOAD;
            }
            if ("DEBUG".equals(subcommand)) {
                return SCRIPT_DEBUG;
            }
        }
        return lookup(command);
    }

    static void requireOrdinary(String command, CommandArgs args) {
        if (args == null) {
            throw new IllegalArgumentException("Arguments are required");
        }
        CommandSpec spec = resolve(command, args);
        if (spec != null && spec.connection != CommandSpec.Connection.ORDINARY) {
            throw new IllegalArgumentException("Command requires a dedicated API: " + command);
        }
    }

    static void requireTransaction(String command, CommandArgs args) {
        // Blocking commands do not block inside MULTI, but are deliberately excluded from this helper.
        requireOrdinary(command, args);
    }

    private static void register(Map<String, CommandSpec> specs, CommandSpec.Keys keys,
                                 CommandSpec.Connection connection, String names) {
        for (String name : names.split(" ")) {
            if (specs.put(name, new CommandSpec(name, keys, connection, CommandSpec.Access.UNKNOWN, null)) != null) {
                throw new IllegalStateException("Duplicate command metadata: " + name);
            }
        }
    }

    private static void describe(Map<String, CommandSpec> specs, CommandSpec.Access access,
                                 String since, String names) {
        for (String name : names.split(" ")) {
            CommandSpec old = specs.get(name);
            specs.put(name, new CommandSpec(name, old.keys, old.connection, access, since));
        }
    }
}
