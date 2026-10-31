package ru.geministation;

import android.util.Log;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;
import org.json.JSONArray;

/** Fixed-size, in-memory diagnostic events; never include keys or conversation text. */
final class AppLog {
    private static final int LIMIT = 1500;
    private static final ArrayDeque<String> entries = new ArrayDeque<>();
    static synchronized void add(String event) {
        String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT).format(new Date());
        if (entries.size() >= LIMIT) entries.removeFirst();
        entries.addLast(timestamp + "  " + event);
        Log.i("GeminiStation", event);
    }
    static synchronized JSONArray snapshot() {
        JSONArray result = new JSONArray();
        for (String event : entries) result.put(event);
        return result;
    }
    private AppLog() { }
}
