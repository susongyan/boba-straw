package io.github.susongyan.bobastraw;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** One reply, not a snapshot. Duplicates are retained; an empty page may be unfinished. */
public final class ScanPage<T> {
    private final String cursor;
    private final List<T> values;

    ScanPage(String cursor, List<T> values) {
        ScanArgs.validateCursor(cursor);
        this.cursor = cursor;
        this.values = Collections.unmodifiableList(new ArrayList<T>(values));
    }

    public String cursor() {
        return cursor;
    }

    public List<T> values() {
        return values;
    }

    public boolean isFinished() {
        for (int i = 0; i < cursor.length(); i++) {
            if (cursor.charAt(i) != '0') {
                return false;
            }
        }
        return true;
    }
}
