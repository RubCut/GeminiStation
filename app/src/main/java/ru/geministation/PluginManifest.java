package ru.geministation;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.HashSet;
import java.util.Set;

/** Non-executable metadata. Only a deliberately small JSON-schema subset is accepted. */
final class PluginManifest {
    final String id, name, version;
    final boolean shell;
    final JSONArray tools;
    private PluginManifest(String id, String name, String version, boolean shell, JSONArray tools) {
        this.id = id; this.name = name; this.version = version; this.shell = shell; this.tools = tools;
    }
    private static String text(JSONObject object, String key, int max) throws Exception {
        Object value = object.opt(key);
        if (!(value instanceof String) || ((String) value).trim().isEmpty() || ((String) value).length() > max)
            throw new IllegalArgumentException("Некорректное поле: " + key);
        String str = (String) value;
        if (str.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("Недопустимые символы");
        return str;
    }
    static PluginManifest parse(JSONObject json) throws Exception {
        String id = text(json, "id", 24), name = text(json, "name", 80), version = text(json, "version", 32);
        if (!id.matches("[a-z][a-z0-9_]{0,23}")) throw new IllegalArgumentException("Недопустимый id плагина");
        JSONArray permissions = json.optJSONArray("permissions");
        if (json.has("permissions") && permissions == null) throw new IllegalArgumentException("Некорректные разрешения");
        boolean shell = false;
        if (permissions != null) for (int i = 0; i < permissions.length(); i++) {
            if (!"adb_shell".equals(permissions.opt(i)) || shell) throw new IllegalArgumentException("Неизвестное разрешение");
            shell = true;
        }
        JSONArray incoming = json.optJSONArray("tools"), tools = new JSONArray();
        if (incoming == null || incoming.length() < 1 || incoming.length() > 8) throw new IllegalArgumentException("Нужно от 1 до 8 инструментов");
        Set<String> names = new HashSet<>();
        for (int i = 0; i < incoming.length(); i++) {
            JSONObject tool = incoming.getJSONObject(i);
            String local = text(tool, "name", 32), handler = text(tool, "handler", 64), description = text(tool, "description", 500);
            if (!local.matches("[a-z][a-z0-9_]{0,31}") || !handler.matches("[A-Za-z_][A-Za-z0-9_]{0,63}") || !names.add(local))
                throw new IllegalArgumentException("Недопустимое или повторное имя инструмента");
            String global = "plugin_" + id.length() + "_" + id + "_" + local;
            if (global.length() > 64) throw new IllegalArgumentException("Имя инструмента слишком длинное");
            if (tool.has("parameters") && tool.optJSONObject("parameters") == null) throw new IllegalArgumentException("Некорректная схема параметров");
            JSONObject schema = normalizeSchema(tool.optJSONObject("parameters"));
            tools.put(new JSONObject().put("name", global).put("handler", handler).put("description", description).put("parameters", schema));
        }
        return new PluginManifest(id, name, version, shell, tools);
    }
    private static JSONObject normalizeSchema(JSONObject schema) throws Exception {
        if (schema == null) return new JSONObject().put("type", "OBJECT").put("properties", new JSONObject());
        if (!"OBJECT".equals(schema.opt("type"))) throw new IllegalArgumentException("Параметры должны быть OBJECT");
        JSONObject input = schema.optJSONObject("properties"), properties = new JSONObject();
        if (input == null || input.length() > 20) throw new IllegalArgumentException("Некорректные свойства");
        java.util.Iterator<String> keys = input.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (!key.matches("[A-Za-z_][A-Za-z0-9_]{0,47}")) throw new IllegalArgumentException("Недопустимое имя параметра");
            JSONObject item = input.getJSONObject(key); String type = text(item, "type", 10);
            if (!("STRING".equals(type) || "INTEGER".equals(type) || "NUMBER".equals(type) || "BOOLEAN".equals(type)))
                throw new IllegalArgumentException("Неподдерживаемый тип параметра");
            JSONObject normalized = new JSONObject().put("type", type);
            if (item.has("description")) normalized.put("description", text(item, "description", 300));
            properties.put(key, normalized);
        }
        JSONArray required = schema.optJSONArray("required"), copy = new JSONArray();
        if (schema.has("required") && required == null) throw new IllegalArgumentException("Некорректный required"); Set<String> seen = new HashSet<>();
        if (required != null) for (int i = 0; i < required.length(); i++) {
            Object value = required.opt(i);
            if (!(value instanceof String) || !properties.has((String) value) || !seen.add((String) value))
                throw new IllegalArgumentException("Некорректный required");
            copy.put(value);
        }
        JSONObject result = new JSONObject().put("type", "OBJECT").put("properties", properties);
        if (copy.length() > 0) result.put("required", copy);
        return result;
    }
    static boolean validArgs(JSONObject schema, JSONObject args) {
        if (args == null || args.toString().length() > 16384) return false;
        JSONObject properties = schema.optJSONObject("properties"); JSONArray required = schema.optJSONArray("required");
        if (properties == null) return false;
        if (required != null) for (int i=0; i<required.length(); i++) if (!args.has(required.optString(i))) return false;
        java.util.Iterator<String> keys = args.keys();
        while (keys.hasNext()) {
            String key = keys.next(); JSONObject spec = properties.optJSONObject(key); Object value = args.opt(key);
            if (spec == null || value == null || value == JSONObject.NULL) return false;
            String type = spec.optString("type");
            if ("STRING".equals(type)) { if (!(value instanceof String) || ((String) value).length() > 1024) return false; }
            else if ("BOOLEAN".equals(type)) { if (!(value instanceof Boolean)) return false; }
            else if ("INTEGER".equals(type) || "NUMBER".equals(type)) {
                if (!(value instanceof Number)) return false;
                double n = ((Number) value).doubleValue();
                if (!Double.isFinite(n) || Math.abs(n) > 1e12 || ("INTEGER".equals(type) && n != Math.rint(n))) return false;
            } else return false;
        }
        return true;
    }
    private PluginManifest() { id = name = version = null; shell = false; tools = null; }
}
