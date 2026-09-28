package io.github.susongyan.bobastraw;

import io.github.susongyan.bobastraw.protocol.RespValue;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;

/**
 * Explicit one-page String scans over shared connections. Start with cursor "0" and stop only
 * when isFinished() is true. A topology change invalidates iteration guarantees; no replay occurs.
 */
public final class BobaStrawScanCommands {
    private final CommandExecutor executor;
    private final boolean databaseScan;

    BobaStrawScanCommands(CommandExecutor executor, boolean databaseScan) {
        this.executor = executor;
        this.databaseScan = databaseScan;
    }

    public CompletionStage<ScanPage<String>> scan(String cursor) {
        return scan(cursor, ScanArgs.none());
    }

    /** Cluster has no stable per-node database cursor in this facade and rejects this method. */
    public CompletionStage<ScanPage<String>> scan(String cursor, ScanArgs options) {
        if (!databaseScan) {
            throw new UnsupportedOperationException("Cluster database SCAN requires explicit node-bound iteration");
        }
        return execute("SCAN", null, cursor, options, BobaStrawScanCommands::strings);
    }

    public CompletionStage<ScanPage<Map.Entry<String, String>>> hscan(String key, String cursor) {
        return hscan(key, cursor, ScanArgs.none());
    }

    public CompletionStage<ScanPage<Map.Entry<String, String>>> hscan(String key, String cursor, ScanArgs options) {
        requireKey(key);
        return execute("HSCAN", key, cursor, options, value -> pairs(value, CommandDecoders.STRING));
    }

    public CompletionStage<ScanPage<String>> sscan(String key, String cursor) {
        return sscan(key, cursor, ScanArgs.none());
    }

    public CompletionStage<ScanPage<String>> sscan(String key, String cursor, ScanArgs options) {
        requireKey(key);
        return execute("SSCAN", key, cursor, options, BobaStrawScanCommands::strings);
    }

    public CompletionStage<ScanPage<Map.Entry<String, Double>>> zscan(String key, String cursor) {
        return zscan(key, cursor, ScanArgs.none());
    }

    public CompletionStage<ScanPage<Map.Entry<String, Double>>> zscan(String key, String cursor, ScanArgs options) {
        requireKey(key);
        return execute("ZSCAN", key, cursor, options, value -> pairs(value, CommandDecoders.NULLABLE_DOUBLE));
    }

    private <T> CompletionStage<ScanPage<T>> execute(String name, String key, String cursor, ScanArgs options,
                                                   CommandDecoder<List<T>> decoder) {
        if (options == null) {
            throw new IllegalArgumentException("Scan options are required");
        }
        return executor.execute(new TypedCommand<ScanPage<T>>(name, value -> page(value, decoder),
            options.arguments(key, cursor)));
    }

    static <T> ScanPage<T> page(RespValue value, CommandDecoder<List<T>> decoder) {
        List<RespValue> outer = array(value);
        if (outer.size() != 2) {
            throw new BobaStrawProtocolException("Expected cursor and values in scan reply");
        }
        String cursor = outer.get(0).asString();
        try {
            ScanArgs.validateCursor(cursor);
        } catch (IllegalArgumentException error) {
            throw new BobaStrawProtocolException("Invalid cursor in scan reply");
        }
        return new ScanPage<T>(cursor, decoder.apply(outer.get(1)));
    }

    private static List<String> strings(RespValue value) {
        List<String> values = new ArrayList<String>();
        for (RespValue item : array(value)) {
            String text = item.asString();
            if (text == null) {
                throw new BobaStrawProtocolException("Null scan element");
            }
            values.add(text);
        }
        return values;
    }

    private static <T> List<Map.Entry<String, T>> pairs(RespValue value, CommandDecoder<T> decoder) {
        List<RespValue> items = array(value);
        if (items.size() % 2 != 0) {
            throw new BobaStrawProtocolException("Expected pairs in scan reply");
        }
        List<Map.Entry<String, T>> values = new ArrayList<Map.Entry<String, T>>();
        for (int i = 0; i < items.size(); i += 2) {
            String key = items.get(i).asString();
            T decoded = decoder.apply(items.get(i + 1));
            if (key == null || decoded == null) {
                throw new BobaStrawProtocolException("Null scan pair");
            }
            values.add(new AbstractMap.SimpleImmutableEntry<String, T>(key, decoded));
        }
        return values;
    }

    private static List<RespValue> array(RespValue value) {
        if (!(value instanceof RespValue.Array)) {
            throw new BobaStrawProtocolException("Expected scan array");
        }
        return ((RespValue.Array) value).values;
    }

    private static void requireKey(String key) {
        if (key == null) {
            throw new IllegalArgumentException("Scan key is required");
        }
    }
}
