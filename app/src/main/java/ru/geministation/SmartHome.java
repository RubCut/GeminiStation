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
    private static volatile Snapshot cached;
    private static volatile long cachedAt;
    static final class Device {
        final String id, name, room, home, scope;
        final JSONObject raw;
        Device(String id, String name, String room) { this(id, name, room, "", HomeSelection.key("", room), new JSONObject()); }
        Device(String id, String name, String room, String home, String scope, JSONObject raw) {
            this.id = id; this.name = name; this.room = room; this.home = home; this.scope = scope; this.raw = raw;
        }
    }
    static final class Scope {
        final String id, homeId, homeName, roomName;
        Scope(String id, String homeId, String homeName, String roomName) {
            this.id = id; this.homeId = homeId; this.homeName = homeName; this.roomName = roomName;
        }
    }
    static final class Snapshot {
        final List<Device> devices = new ArrayList<>();
        final java.util.Map<String, Scope> scopes = new java.util.LinkedHashMap<>();
        String identity;
        long loadedAt;
        JSONArray prompt() throws Exception {
            JSONArray out = new JSONArray();
            for (int i = 0; i < devices.size(); i++) {
                Device d = devices.get(i);
                JSONObject item = new JSONObject().put("id", i).put("name", d.name).put("room", d.room).put("home", d.home)
                        .put("capabilities", HomeCapabilities.prompt(d.raw)).put("properties", HomeCapabilities.properties(d.raw))
                        .put("snapshot_age_seconds", Math.max(0, (System.currentTimeMillis() - loadedAt) / 1000));
                String type = d.raw.optString("type", "");
                if (type.matches("devices\\.types\\.[a-z0-9_.]{1,80}")) item.put("type", type);
                out.put(item);
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
        java.util.Map<String, String> homes = new java.util.HashMap<>(), roomNames = new java.util.HashMap<>(), roomHomes = new java.util.HashMap<>();
        JSONArray houseList = root.optJSONArray("households"), roomList = root.optJSONArray("rooms");
        if (houseList != null) for (int i = 0; i < houseList.length() && i < 100; i++) {
            JSONObject h = houseList.optJSONObject(i); if (h == null) continue;
            String id = h.optString("id", ""), name = safeName(h.optString("name", ""));
            if (!id.isEmpty() && id.length() <= 200) homes.put(id, name.isEmpty() ? "Дом" : name);
        }
        if (roomList != null) for (int i = 0; i < roomList.length() && i < 300; i++) {
            JSONObject r = roomList.optJSONObject(i); if (r == null) continue;
            String id = r.optString("id", ""), house = r.optString("household_id", ""), name = safeName(r.optString("name", ""));
            if (id.isEmpty() || id.length() > 200 || house.length() > 200) continue;
            roomNames.put(id, name.isEmpty() ? "Комната" : name); roomHomes.put(id, house);
            addScope(out, house, id, homes.getOrDefault(house, "Без дома"), roomNames.get(id));
        }
        JSONArray devices = root.optJSONArray("devices");
        if (devices == null) return out;
        for (int i = 0; i < devices.length() && out.devices.size() < 100; i++) {
            JSONObject d = devices.optJSONObject(i); if (d == null) continue;
            String id = d.optString("id", ""), name = safeName(d.optString("name", ""));
            if (id.isEmpty() || id.length() > 200 || name.isEmpty()) continue;
            String room = d.optString("room", ""), house = d.optString("household_id", "");
            if (room.length() > 200 || house.length() > 200) continue;
            if (house.isEmpty()) house = roomHomes.getOrDefault(room, "");
            // Do not attribute an inconsistent room to a different household.
            if (roomHomes.containsKey(room) && !roomHomes.get(room).isEmpty() && !roomHomes.get(room).equals(house)) continue;
            String roomName = room.isEmpty() ? "Без комнаты" : roomNames.getOrDefault(room, safeName(room));
            String houseName = homes.getOrDefault(house, house.isEmpty() ? "Без дома" : "Дом");
            String scope = addScope(out, house, room, houseName, roomName);
            out.devices.add(new Device(id, name, roomName, houseName, scope, d));
        }
        return out;
    }
    private static String addScope(Snapshot out, String house, String room, String houseName, String roomName) {
        String key = HomeSelection.key(house, room);
        out.scopes.put(key, new Scope(key, HomeSelection.key(house, "\u0000house"), houseName, roomName));
        return key;
    }
    static Snapshot filtered(Context c, Snapshot full) {
        Snapshot out = new Snapshot(); out.identity = full.identity; out.loadedAt = full.loadedAt; out.scopes.putAll(full.scopes);
        try { if (full.identity == null || !full.identity.equals(identity(c))) return out; } catch (Exception e) { return out; }
        String setting = HomeSelection.get(c);
        for (Device d : full.devices) if (HomeSelection.allowed(setting, full.identity, d.scope)) out.devices.add(d);
        return out;
    }
    static JSONArray scopeList(Context c, Snapshot full) throws Exception {
        JSONArray out = new JSONArray(); String setting = HomeSelection.get(c);
        for (Scope scope : full.scopes.values()) out.put(new JSONObject().put("id", scope.id).put("homeId", scope.homeId)
                .put("home", scope.homeName).put("room", scope.roomName)
                .put("selected", HomeSelection.allowed(setting, full.identity, scope.id)));
        return out;
    }
    static String identity(Context c) throws Exception {
        String setting = new SecretStore(c, PREFS).load();
        String config = QR.equals(setting) ? new SecretStore(c, "led_secrets").load() : "";
        byte[] hash = java.security.MessageDigest.getInstance("SHA-256").digest((setting + "\n" + config).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return android.util.Base64.encodeToString(hash, android.util.Base64.NO_WRAP);
    }
    /** Safe stale snapshot for a slow network: account changes still fail closed. */
    static Snapshot cachedForCurrentIdentity(Context c) {
        Snapshot current = cached;
        if (current == null || current.identity == null || android.os.SystemClock.elapsedRealtime() - cachedAt > 120000) return null;
        try { return current.identity.equals(identity(c)) ? filtered(c, current) : null; }
        catch (Exception e) { return null; }
    }
    static Snapshot load(Context c) throws Exception { return filtered(c, loadFull(c)); }
    static synchronized Snapshot loadFull(Context c) throws Exception {
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
        snapshot.loadedAt = System.currentTimeMillis();
        AppLog.add("Умный дом: список получен, устройств " + snapshot.devices.size());
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
        List<HomeCapabilities.Capability> caps = HomeCapabilities.parse(device.raw);
        for (int i = 0; i < caps.size(); i++) if ("devices.capabilities.on_off".equals(caps.get(i).type))
            return setCapability(c, snapshot, device, i, on ? "true" : "false");
        throw new IOException("Устройство не поддерживает включение и выключение");
    }
    static boolean setCapability(Context c, Snapshot snapshot, Device device, int index, String input) throws Exception {
        List<HomeCapabilities.Capability> caps = HomeCapabilities.parse(device.raw);
        if (!snapshot.devices.contains(device) || index < 0 || index >= caps.size()) throw new IOException("capability missing");
        HomeCapabilities.Capability cap = caps.get(index); Object value = cap.value(input);
        if (value == null) throw new IOException("invalid capability value");
        if (snapshot.identity == null || !snapshot.identity.equals(identity(c)) || !HomeSelection.permitted(c, snapshot.identity, device.scope)) throw new IOException("account or scope changed");
        String token = token(c);
        // Refresh is read-only and may take seconds; do not block settings changes while waiting.
        JSONObject current = YandexCloud.home(token, "/v1.0/user/info", null);
        if (!"ok".equals(current.optString("status")) || current.optJSONArray("devices") == null) throw new IOException("home refresh failed");
        Device live = null;
        for (Device candidate : parse(current).devices) if (device.id.equals(candidate.id)) {
            if (live != null) throw new IOException("ambiguous device");
            live = candidate;
        }
        if (live == null || !device.scope.equals(live.scope)) throw new IOException("room changed");
        HomeCapabilities.Capability liveCap = null;
        for (HomeCapabilities.Capability candidate : HomeCapabilities.parse(live.raw)) if (cap.type.equals(candidate.type) && cap.instance.equals(candidate.instance)) {
            if (liveCap != null) throw new IOException("ambiguous capability");
            liveCap = candidate;
        }
        if (liveCap == null || liveCap.value(input) == null) throw new IOException("capability changed");
        value = liveCap.value(input);
        JSONObject action = new JSONObject().put("type", cap.type).put("state", new JSONObject().put("instance", cap.instance).put("value", value));
        JSONObject body = new JSONObject().put("devices", new JSONArray().put(new JSONObject().put("id", device.id).put("actions", new JSONArray().put(action))));
        // Hold the lock through the actual command so revoked scope cannot race its dispatch.
        synchronized (HomeSelection.LOCK) {
            if (!snapshot.identity.equals(identity(c)) || !HomeSelection.permitted(c, snapshot.identity, live.scope)) throw new IOException("account or scope changed");
            JSONObject result;
            try { result = YandexCloud.home(token, "/v1.0/devices/actions", body); }
            catch (IOException e) { if (controlForbidden(e)) throw new ControlDenied(); throw e; }
            String outcome = HomeDiagnostics.outcome(result, device.id, cap.type, cap.instance);
            AppLog.add("Умный дом: результат команды " + outcome);
            cached = null;
            return "DONE".equals(outcome);
        }
    }
    private SmartHome() { }
}
