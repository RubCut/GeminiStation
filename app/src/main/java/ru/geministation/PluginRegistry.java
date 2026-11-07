package ru.geministation;

import android.content.Context;
import android.util.AtomicFile;
import android.util.Base64;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/** Private, consent-gated plugin store. Never executes plugin code while staging. */
final class PluginRegistry {
    static final class Record {
        final PluginArchive archive;
        final byte[] zip;
        final boolean enabled;
        Record(PluginArchive archive, byte[] zip, boolean enabled) { this.archive = archive; this.zip = zip; this.enabled = enabled; }
    }
    static final class Pending {
        final String token, priorDigest;
        final long expiresAt;
        final Record record;
        Pending(Record record, String priorDigest) { this.record = record; this.priorDigest = priorDigest; token = UUID.randomUUID().toString(); expiresAt = android.os.SystemClock.elapsedRealtime() + 120000; }
    }
    private static Pending pending;
    // Bounded, in-memory diagnostics: never mixed into the general app log or backups.
    private static final java.util.Map<String, java.util.ArrayDeque<String>> logs = new java.util.HashMap<>();
    private static File directory(Context context) throws Exception {
        File dir = new File(context.getFilesDir(), "plugins");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IllegalStateException("Не удалось создать каталог");
        return dir;
    }
    private static File path(Context context, String id) throws Exception {
        if (id == null || !id.matches("[a-z][a-z0-9_]{0,23}")) throw new IllegalArgumentException("Некорректный id");
        return new File(directory(context), id + ".json");
    }
    private static Record read(Context context, String id) throws Exception {
        File file = path(context, id);
        if (!file.exists()) return null;
        byte[] bytes;
        try (FileInputStream in = new AtomicFile(file).openRead(); java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            byte[] chunk = new byte[4096]; int n;
            while ((n = in.read(chunk)) != -1) { if (out.size() + n > 220000) throw new IllegalArgumentException("Файл плагина слишком большой"); out.write(chunk, 0, n); }
            bytes = out.toByteArray();
        }
        JSONObject data = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
        byte[] zip = Base64.decode(data.getString("zip"), Base64.NO_WRAP);
        PluginArchive archive = PluginArchive.parse(zip);
        if (!id.equals(archive.manifest.id)) throw new IllegalArgumentException("Плагин не совпадает");
        return new Record(archive, zip, data.optBoolean("enabled", false));
    }
    private static void write(Context context, Record record) throws Exception {
        AtomicFile file = new AtomicFile(path(context, record.archive.manifest.id));
        byte[] bytes = new JSONObject().put("zip", Base64.encodeToString(record.zip, Base64.NO_WRAP))
                .put("enabled", record.enabled).toString().getBytes(StandardCharsets.UTF_8);
        FileOutputStream out = null;
        try { out = file.startWrite(); out.write(bytes); file.finishWrite(out); }
        catch (Exception e) { if (out != null) file.failWrite(out); throw e; }
    }
    static synchronized Pending stage(Context c, byte[] zip) throws Exception {
        expirePending();
        if (pending != null) throw new IllegalStateException("Дождитесь ответа на станции");
        PluginArchive archive = PluginArchive.parse(zip); Record old = read(c, archive.manifest.id);
        if (old == null && list(c).size() >= 20) throw new IllegalArgumentException("Не более 20 плагинов");
        pending = new Pending(new Record(archive, zip.clone(), true), old == null ? null : old.archive.digest);
        return pending;
    }
    static synchronized Pending stageEnable(Context c, String id) throws Exception {
        expirePending();
        if (pending != null) throw new IllegalStateException("Дождитесь ответа на станции");
        Record old = read(c, id);
        if (old == null) throw new IllegalArgumentException("Плагин не найден");
        pending = new Pending(new Record(old.archive, old.zip, true), old.archive.digest);
        return pending;
    }
    private static void expirePending() { if (pending != null && android.os.SystemClock.elapsedRealtime() >= pending.expiresAt) pending = null; }
    static synchronized Pending pending(String token) { expirePending(); return pending != null && pending.token.equals(token) ? pending : null; }
    static synchronized boolean resolve(Context c, String token, boolean accepted) throws Exception {
        Pending p = pending(token); if (p == null) return false;
        pending = null;
        if (!accepted) return true;
        Record current = read(c, p.record.archive.manifest.id);
        String now = current == null ? null : current.archive.digest;
        if (!java.util.Objects.equals(now, p.priorDigest)) throw new IllegalStateException("Плагин изменился; повторите подтверждение");
        write(c, p.record); return true;
    }
    static synchronized void disable(Context c, String id) throws Exception {
        Record old = read(c, id); if (old == null) throw new IllegalArgumentException("Плагин не найден");
        if (pending != null && pending.record.archive.manifest.id.equals(id)) pending = null;
        write(c, new Record(old.archive, old.zip, false));
    }
    static synchronized void delete(Context c, String id) throws Exception {
        if (pending != null && pending.record.archive.manifest.id.equals(id)) pending = null;
        AtomicFile file = new AtomicFile(path(c, id)); file.delete();
        new AtomicFile(configPath(c, id)).delete();
        logs.remove(id);
    }
    private static File configPath(Context c, String id) throws Exception {
        // Reuse the id validator and private plugin directory; not matched by list().
        path(c, id);
        return new File(directory(c), id + ".config");
    }
    static synchronized JSONObject config(Context c, String id, String digest) throws Exception {
        Record record = read(c, id);
        if (record == null || (digest != null && !digest.equals(record.archive.digest)))
            throw new IllegalArgumentException("Плагин не найден или изменился");
        File file = configPath(c, id);
        if (!file.exists() && !new File(file.getPath() + ".bak").exists()) {
            JSONObject defaults = record.archive.manifestJson.optJSONObject("settings");
            return defaults == null ? new JSONObject() : new JSONObject(defaults.toString());
        }
        try (FileInputStream in = new AtomicFile(file).openRead(); java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            byte[] buffer = new byte[1024]; int n;
            while ((n = in.read(buffer)) != -1) {
                if (out.size() + n > 8192) throw new IllegalStateException("Файл настроек слишком большой");
                out.write(buffer, 0, n);
            }
            return new JSONObject(new String(out.toByteArray(), StandardCharsets.UTF_8));
        }
    }
    static synchronized void saveConfig(Context c, String id, String digest, JSONObject config) throws Exception {
        Record record = read(c, id);
        if (record == null || digest == null || !digest.equals(record.archive.digest))
            throw new IllegalArgumentException("Плагин не найден или изменился; обновите список");
        if (config == null) throw new IllegalArgumentException("Настройки должны быть JSON-объектом");
        byte[] bytes = config.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 8192) throw new IllegalArgumentException("Настройки больше 8 КиБ");
        AtomicFile file = new AtomicFile(configPath(c, id)); FileOutputStream out = null;
        try { out = file.startWrite(); out.write(bytes); file.finishWrite(out); }
        catch (Exception e) { if (out != null) file.failWrite(out); throw e; }
    }
    static synchronized void resetConfig(Context c, String id, String digest) throws Exception {
        Record record = read(c, id);
        if (record == null || digest == null || !digest.equals(record.archive.digest))
            throw new IllegalArgumentException("Плагин не найден или изменился; обновите список");
        new AtomicFile(configPath(c, id)).delete();
    }
    static synchronized void log(Context c, String id, String digest, String message) {
        try {
            Record record = read(c, id);
            if (record == null || !record.archive.digest.equals(digest)) return;
            java.util.ArrayDeque<String> entries = logs.computeIfAbsent(id, k -> new java.util.ArrayDeque<>());
            String bounded = message.length() > 4200 ? message.substring(0, 4200) + "…" : message;
            entries.addLast(new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.ROOT).format(new java.util.Date()) + " " + bounded);
            while (entries.size() > 30) entries.removeFirst();
        } catch (Exception ignored) { }
    }
    static synchronized JSONArray logs(Context c, String id) throws Exception {
        if (read(c, id) == null) throw new IllegalArgumentException("Плагин не найден");
        JSONArray result = new JSONArray();
        java.util.ArrayDeque<String> entries = logs.get(id);
        if (entries != null) for (String entry : entries) result.put(entry);
        return result;
    }
    static synchronized void clearLogs(Context c, String id) throws Exception {
        if (read(c, id) == null) throw new IllegalArgumentException("Плагин не найден");
        logs.remove(id);
    }
    static synchronized List<Record> list(Context c) throws Exception {
        File[] files = directory(c).listFiles((dir, name) -> name.matches("[a-z][a-z0-9_]{0,23}\\.json"));
        List<Record> result = new ArrayList<>();
        if (files == null) return result;
        Arrays.sort(files, java.util.Comparator.comparing(File::getName));
        for (File file : files) {
            String id = file.getName().substring(0, file.getName().length() - 5);
            try { Record record = read(c, id); if (record != null) result.add(record); }
            catch (Exception ignored) { AppLog.add("Плагины: сохранённый файл не удалось прочитать"); }
        }
        return result;
    }
    static synchronized boolean permitted(Context c, String id, String digest, boolean requireShell) {
        try {
            Record record = read(c, id);
            return record != null && record.enabled && digest.equals(record.archive.digest) && (!requireShell || record.archive.manifest.shell);
        } catch (Exception e) { return false; }
    }
    static JSONArray describe(List<Record> records) throws Exception {
        JSONArray out = new JSONArray();
        for (Record r : records) out.put(new JSONObject().put("id", r.archive.manifest.id).put("name", r.archive.manifest.name)
                .put("version", r.archive.manifest.version).put("enabled", r.enabled).put("shell", r.archive.manifest.shell)
                .put("sha256", r.archive.digest).put("toolCount", r.archive.manifest.tools.length()));
        return out;
    }
    private PluginRegistry() { }
}
