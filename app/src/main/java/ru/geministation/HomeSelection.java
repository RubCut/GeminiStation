package ru.geministation;

import android.content.Context;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Room allowlist bound to the current account; an account change fails closed. */
final class HomeSelection {
    static final Object LOCK = new Object();
    private static final String PREFS = "home_scope";
    static String key(String home, String room) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest((home.length() + ":" + home + room).getBytes(StandardCharsets.UTF_8));
            StringBuilder s = new StringBuilder();
            for (byte b : bytes) s.append(String.format(java.util.Locale.US, "%02x", b & 255));
            return s.toString();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
    static boolean allowed(String setting, String identity, String roomKey) {
        if (setting == null || setting.isEmpty()) return true; // Existing installations retain their prior scope.
        try {
            JSONObject data = new JSONObject(setting);
            if (identity == null || !identity.equals(data.optString("identity"))) return false;
            JSONArray rooms = data.optJSONArray("rooms");
            if (rooms == null) return false;
            for (int i = 0; i < rooms.length(); i++) if (roomKey.equals(rooms.optString(i))) return true;
        } catch (Exception ignored) { }
        return false;
    }
    static String get(Context c) { return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("selection", ""); }
    static boolean permitted(Context c, String identity, String roomKey) { return allowed(get(c), identity, roomKey); }
    static boolean save(Context c, SmartHome.Snapshot full, JSONArray requested) throws Exception {
        synchronized (LOCK) {
        if (full.identity == null || !full.identity.equals(SmartHome.identity(c)) || requested == null || requested.length() > 300) return false;
        Set<String> valid = new HashSet<>(full.scopes.keySet()), chosen = new HashSet<>();
        for (int i = 0; i < requested.length(); i++) {
            Object item = requested.opt(i);
            if (!(item instanceof String) || !valid.contains(item) || !chosen.add((String) item)) return false;
        }
        String value = new JSONObject().put("identity", full.identity).put("rooms", new JSONArray(chosen)).toString();
        return full.identity.equals(SmartHome.identity(c)) && c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString("selection", value).commit();
        }
    }
    private HomeSelection() { }
}
