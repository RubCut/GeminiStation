package ru.geministation;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.json.JSONArray;
import org.json.JSONObject;

/** Encodes the encrypted payload; legacy single-key payloads are migrated on first read. */
final class KeyListCodec {
    private static final String PREFIX = "GeminiKeyListV1:";
    static boolean legacy(String value) { return !value.isEmpty() && !value.startsWith(PREFIX); }
    static List<KeyRotation.Entry> decode(String value, Supplier<String> newId) throws Exception {
        List<KeyRotation.Entry> keys = new ArrayList<>();
        if (value.isEmpty()) return keys;
        if (legacy(value)) {
            keys.add(new KeyRotation.Entry(newId.get(), value));
            return keys;
        }
        JSONObject object = new JSONObject(value.substring(PREFIX.length()));
        if (object.getInt("version") != 1) throw new IllegalStateException("Unsupported key store version");
        JSONArray array = object.getJSONArray("keys");
        if (array.length() > 10) throw new IllegalStateException("Too many keys");
        for (int i = 0; i < array.length(); i++) {
            JSONObject entry = array.getJSONObject(i);
            String id = entry.getString("id"), secret = entry.getString("secret");
            if (id.isEmpty() || secret.isEmpty()) throw new IllegalStateException("Invalid key store");
            keys.add(new KeyRotation.Entry(id, secret));
        }
        return keys;
    }
    static String encode(List<KeyRotation.Entry> keys) throws Exception {
        JSONArray array = new JSONArray();
        for (KeyRotation.Entry entry : keys) array.put(new JSONObject().put("id", entry.id).put("secret", entry.secret));
        return PREFIX + new JSONObject().put("version", 1).put("keys", array).toString();
    }
    private KeyListCodec() { }
}
