package ru.geministation;

import android.content.Context;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Snapshot IDs are local indexes, never raw device IDs supplied by the model. */
final class SmartHome {
    static final String PREFS = "home_secrets";
    static final String QR = "qr";
    private static Snapshot cached;
    private static long cachedAt;
    static final class Device {
        final String id, name, room;
        Device(String id, String name, String room) { this.id = id; this.name = name; this.room = room; }
    }
    static final class Snapshot {
        final List<Device> devices = new ArrayList<>();
        String identity;
        JSONArray prompt() throws Exception {
            JSONArray out = new JSONArray();
            for (int i = 0; i < devices.size(); i++) {
                Device d = devices.get(i);
                out.put(new JSONObject().put("id", i).put("name", d.name).put("room", d.room));
            }
            return out;
        }
    }
    static String token(Context c) throws Exception {
        String setting = new SecretStore(c, PREFS).load();
        if (setting.isEmpty()) throw new IOException("Умный дом не подключён");
        if (QR.equals(setting)) return YandexCloud.musicToken(c);
        return setting;
    }
    static String safeName(String s) {
        s = s.replaceAll("[\\p{Cntrl}]", " ").trim();
        return s.substring(0, Math.min(s.length(), 100));
    }
    static Snapshot parse(JSONObject root) {
        Snapshot out = new Snapshot();
        JSONArray devices = root.optJSONArray("devices");
        if (devices == null) return out;
        for (int i = 0; i < devices.length() && out.devices.size() < 100; i++) {
            JSONObject d = devices.optJSONObject(i);
            if (d == null) continue;
            String id = d.optString("id", ""), name = safeName(d.optString("name", ""));
            if (id.isEmpty() || id.length() > 200 || name.isEmpty()) continue;
            JSONArray caps = d.optJSONArray("capabilities");
            if (caps == null) continue;
            for (int j = 0; j < caps.length(); j++) {
                JSONObject cap = caps.optJSONObject(j);
                if (cap != null && "devices.capabilities.on_off".equals(cap.optString("type"))) {
                    out.devices.add(new Device(id, name, safeName(d.optString("room", "")))); break;
                }
            }
        }
        return out;
    }
    static String identity(Context c) throws Exception {
        String setting = new SecretStore(c, PREFS).load();
        String config = QR.equals(setting) ? new SecretStore(c, "led_secrets").load() : "";
        byte[] hash = java.security.MessageDigest.getInstance("SHA-256").digest((setting + "\n" + config).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return android.util.Base64.encodeToString(hash, android.util.Base64.NO_WRAP);
    }
    static synchronized Snapshot load(Context c) throws Exception {
        String identity = identity(c);
        long now = android.os.SystemClock.elapsedRealtime();
        if (cached != null && identity.equals(cached.identity) && now - cachedAt < 30000) {
            AppLog.add("Умный дом: список из кэша, устройств " + cached.devices.size());
            return cached;
        }
        JSONObject result = YandexCloud.home(token(c), "/v1.0/user/info", null);
        if (!"ok".equals(result.optString("status")) || result.optJSONArray("devices") == null) throw new IOException("bad response");
        if (!identity.equals(identity(c))) throw new IOException("account changed");
        Snapshot snapshot = parse(result);
        snapshot.identity = identity;
        AppLog.add("Умный дом: список получен, устройств с on_off " + snapshot.devices.size());
        cached = snapshot;
        cachedAt = android.os.SystemClock.elapsedRealtime();
        return snapshot;
    }
    static JSONObject actionBody(Device device, boolean on) throws Exception {
        JSONObject state = new JSONObject().put("instance", "on").put("value", on);
        JSONObject action = new JSONObject().put("type", "devices.capabilities.on_off").put("state", state);
        return new JSONObject().put("devices", new JSONArray().put(new JSONObject().put("id", device.id)
                .put("actions", new JSONArray().put(action))));
    }
    static boolean accepted(JSONObject response, Device device) {
        if (!"ok".equals(response.optString("status"))) return false;
        JSONArray devices = response.optJSONArray("devices");
        if (devices == null) return false;
        for (int i = 0; i < devices.length(); i++) {
            JSONObject d = devices.optJSONObject(i);
            if (d == null || !device.id.equals(d.optString("id"))) continue;
            JSONArray caps = d.optJSONArray("capabilities");
            if (caps == null) return false;
            for (int j = 0; j < caps.length(); j++) {
                JSONObject cap = caps.optJSONObject(j);
                if (cap == null || !"devices.capabilities.on_off".equals(cap.optString("type"))) continue;
                JSONObject state = cap.optJSONObject("state");
                JSONObject result = state == null ? null : state.optJSONObject("action_result");
                if (state != null && "on".equals(state.optString("instance")) && result != null
                        && "DONE".equals(result.optString("status"))) return true;
            }
        }
        return false;
    }
    static final class ControlDenied extends IOException {
        ControlDenied() { super("HTTP 403"); }
    }
    static boolean controlForbidden(Exception error) {
        return error instanceof IOException && "HTTP 403".equals(error.getMessage());
    }
    static boolean set(Context c, Snapshot snapshot, Device device, boolean on) throws Exception {
        if (snapshot.identity == null || !snapshot.identity.equals(identity(c))) throw new IOException("account changed");
        String token = token(c);
        if (!snapshot.identity.equals(identity(c))) throw new IOException("account changed");
        AppLog.add("Умный дом: отправка on_off (" + (on ? "включение" : "выключение") + ")");
        JSONObject result;
        try { result = YandexCloud.home(token, "/v1.0/devices/actions", actionBody(device, on)); }
        catch (IOException e) {
            if (controlForbidden(e)) throw new ControlDenied();
            throw e;
        }
        AppLog.add("Умный дом: результат команды " + HomeDiagnostics.outcome(result, device.id));
        return accepted(result, device);
    }
    private SmartHome() { }
}
