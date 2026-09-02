package io.github.susongyan.bobastraw;

import java.util.ArrayList;
import java.util.List;

/**
 * Optional arguments for Redis {@code SET}.
 *
 * <p>Instances are immutable. Use the static factory methods and chain the
 * desired options, for example {@code SetArgs.nx().ex(30)}.</p>
 */
public final class SetArgs {
    private final String condition;
    private final String expirationUnit;
    private final Long expiration;
    private final boolean keepTtl;
    private final boolean returnOldValue;

    private SetArgs(
        String condition,
        String expirationUnit,
        Long expiration,
        boolean keepTtl,
        boolean returnOldValue
    ) {
        this.condition = condition;
        this.expirationUnit = expirationUnit;
        this.expiration = expiration;
        this.keepTtl = keepTtl;
        this.returnOldValue = returnOldValue;
    }

    public static SetArgs none() {
        return new SetArgs(null, null, null, false, false);
    }

    public static SetArgs nx() {
        return none().onlyIfAbsent();
    }

    public static SetArgs xx() {
        return none().onlyIfPresent();
    }

    public SetArgs onlyIfAbsent() {
        return new SetArgs("NX", expirationUnit, expiration, keepTtl, returnOldValue);
    }

    public SetArgs onlyIfPresent() {
        return new SetArgs("XX", expirationUnit, expiration, keepTtl, returnOldValue);
    }

    public SetArgs ex(long seconds) {
        return expiration("EX", seconds);
    }

    public SetArgs px(long milliseconds) {
        return expiration("PX", milliseconds);
    }

    public SetArgs exAt(long unixSeconds) {
        return expiration("EXAT", unixSeconds);
    }

    public SetArgs pxAt(long unixMilliseconds) {
        return expiration("PXAT", unixMilliseconds);
    }

    public SetArgs keepTtl() {
        if (expiration != null) {
            throw new IllegalStateException("KEEPTTL cannot be combined with an expiration");
        }
        return new SetArgs(condition, null, null, true, returnOldValue);
    }

    /**
     * Requests Redis 6.2+ to return the previous value. A null reply means
     * that an NX/XX condition prevented the write or the key did not exist.
     */
    public SetArgs returnOldValue() {
        return new SetArgs(condition, expirationUnit, expiration, keepTtl, true);
    }

    String[] arguments() {
        List<String> result = new ArrayList<String>();
        if (condition != null) {
            result.add(condition);
        }
        if (expiration != null) {
            result.add(expirationUnit);
            result.add(Long.toString(expiration.longValue()));
        }
        if (keepTtl) {
            result.add("KEEPTTL");
        }
        if (returnOldValue) {
            result.add("GET");
        }
        return result.toArray(new String[result.size()]);
    }

    private SetArgs expiration(String unit, long value) {
        if (value <= 0) {
            throw new IllegalArgumentException("SET expiration must be positive");
        }
        if (keepTtl) {
            throw new IllegalStateException("An expiration cannot be combined with KEEPTTL");
        }
        return new SetArgs(condition, unit, Long.valueOf(value), false, returnOldValue);
    }
}
