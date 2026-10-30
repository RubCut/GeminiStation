package ru.geministation;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;
import org.json.JSONArray;

/** Fixed-size, in-memory diagnostic events; never include keys or conversation text. */
final class AppLog {
    private static final int LIMIT = 200;
    private static final ArrayDeque<String> entries = new ArrayDeque<>();
    static synchronized void add(String event) {
        String timestamp = new SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(new Date());
        if (entries.size() >= LIMIT) entries.removeFirst();
        entries.addLast(timestamp + "  " + event);
    }
    static synchronized JSONArray snapshot() {
        JSONArray result = new JSONArray();
        for (String event : entries) result.put(event);
        return result;
    }
    private AppLog() { }
}
