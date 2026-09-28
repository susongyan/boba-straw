package io.github.susongyan.bobastraw;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/** Immutable Redis 5-compatible options. COUNT is a hint, not a page-size limit. */
public final class ScanArgs {
    private static final BigInteger MAX_CURSOR = new BigInteger("18446744073709551615");
    private final String pattern;
    private final Long count;

    private ScanArgs(String pattern, Long count) {
        this.pattern = pattern;
        this.count = count;
    }

    public static ScanArgs none() {
        return new ScanArgs(null, null);
    }

    public ScanArgs match(String pattern) {
        if (pattern == null) {
            throw new IllegalArgumentException("MATCH pattern is required");
        }
        return new ScanArgs(pattern, count);
    }

    public ScanArgs count(long count) {
        if (count <= 0) {
            throw new IllegalArgumentException("COUNT must be positive");
        }
        return new ScanArgs(pattern, count);
    }

    String[] arguments(String key, String cursor) {
        validateCursor(cursor);
        List<String> args = new ArrayList<String>();
        if (key != null) {
            args.add(key);
        }
        args.add(cursor);
        if (pattern != null) {
            args.add("MATCH");
            args.add(pattern);
        }
        if (count != null) {
            args.add("COUNT");
            args.add(count.toString());
        }
        return args.toArray(new String[args.size()]);
    }

    static void validateCursor(String cursor) {
        if (cursor == null || cursor.isEmpty() || cursor.length() > 20) {
            throw new IllegalArgumentException("Cursor must be an unsigned 64-bit decimal string");
        }
        for (int i = 0; i < cursor.length(); i++) {
            if (cursor.charAt(i) < '0' || cursor.charAt(i) > '9') {
                throw new IllegalArgumentException("Cursor must contain decimal digits only");
            }
        }
        if (new BigInteger(cursor).compareTo(MAX_CURSOR) > 0) {
            throw new IllegalArgumentException("Cursor exceeds unsigned 64-bit range");
        }
    }
}
