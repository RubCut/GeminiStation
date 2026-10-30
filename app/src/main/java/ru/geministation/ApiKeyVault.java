package ru.geministation;

import android.content.Context;
import java.util.List;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/** Encrypted Gemini key list. The original single-key secret migrates without losing it. */
final class ApiKeyVault {
    static final int MAX_KEYS = 10;
    private static ApiKeyVault instance;
    private final SecretStore store;
    final KeyRotation rotation = new KeyRotation(() -> AppLog.add("Gemini: лимит 429, переключаем API-ключ"));

    static synchronized ApiKeyVault get(Context context) {
        if (instance == null) instance = new ApiKeyVault(context);
        return instance;
    }
    private ApiKeyVault(Context context) { store = new SecretStore(context); }

    synchronized List<KeyRotation.Entry> keys() throws Exception {
        String value = store.load();
        List<KeyRotation.Entry> keys = KeyListCodec.decode(value, () -> UUID.randomUUID().toString());
        if (KeyListCodec.legacy(value)) save(keys); // keep the old key with a stable opaque ID
        return keys;
    }
    synchronized JSONArray metadata() throws Exception {
        JSONArray result = new JSONArray();
        for (KeyRotation.Entry key : keys()) result.put(new JSONObject().put("id", key.id));
        return result;
    }
    static boolean valid(String key) {
        return key != null && !key.isEmpty() && key.length() <= 512 && !key.matches(".*\\s.*") && !key.chars().anyMatch(Character::isISOControl);
    }
    synchronized void add(String key) throws Exception {
        if (!valid(key)) throw new IllegalArgumentException("Некорректный API-ключ");
        List<KeyRotation.Entry> keys = keys();
        for (KeyRotation.Entry entry : keys) if (entry.secret.equals(key)) throw new IllegalArgumentException("Этот ключ уже добавлен");
        if (keys.size() >= MAX_KEYS) throw new IllegalArgumentException("Можно добавить не более 10 ключей");
        keys.add(new KeyRotation.Entry(UUID.randomUUID().toString(), key));
        save(keys);
    }
    synchronized boolean remove(String id) throws Exception {
        List<KeyRotation.Entry> keys = keys();
        boolean removed = keys.removeIf(entry -> entry.id.equals(id));
        if (removed) save(keys);
        return removed;
    }
    synchronized void clear() { store.clear(); }
    private void save(List<KeyRotation.Entry> keys) throws Exception {
        store.save(KeyListCodec.encode(keys));
    }
    <T> T run(java.util.function.BooleanSupplier active, KeyRotation.Operation<T> op) throws Exception {
        return rotation.run(keys(), active, op);
    }
}
