package ru.geministation;

import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Convert only advertised, well-formed capabilities to validated actions. */
final class HomeCapabilities {
    static final class Capability {
        final String type, instance, kind;
        final double min, max, step;
        final List<String> modes;
        final Object state;
        String unit;
        Object lastUpdated;
        Capability(String type, String instance, String kind, double min, double max, double step, List<String> modes, Object state) {
            this.type = type; this.instance = instance; this.kind = kind; this.min = min; this.max = max; this.step = step; this.modes = modes; this.state = state;
        }
        Object value(String input) {
            if ("boolean".equals(kind)) {
                if ("true".equals(input)) return true;
                if ("false".equals(input)) return false;
                return null;
            }
            if ("mode".equals(kind)) return modes.contains(input) ? input : null;
            if (input == null || !input.matches("-?(?:[0-9]{1,10})(?:\\.[0-9]{1,6})?")) return null;
            try {
                double number = Double.parseDouble(input);
                if (!Double.isFinite(number) || number < min || number > max) return null;
                if (step > 0 && Math.abs((number - min) / step - Math.rint((number - min) / step)) > 0.00001) return null;
                if ("rgb".equals(kind)) return (int) number;
                return number;
            } catch (NumberFormatException e) { return null; }
        }
        JSONObject prompt(int index) throws Exception {
            JSONObject o = new JSONObject().put("index", index).put("type", type.substring("devices.capabilities.".length()))
                    .put("instance", instance).put("value_type", kind);
            if ("number".equals(kind) || "rgb".equals(kind)) o.put("min", min).put("max", max).put("step", step);
            if ("mode".equals(kind)) o.put("modes", new JSONArray(modes));
            if (state != null) o.put("state", state);
            if (unit != null) o.put("unit", unit);
            if (lastUpdated != null) {
                o.put("last_updated", lastUpdated);
                o.put("updated_age_seconds", Math.max(0, (long)(System.currentTimeMillis() / 1000.0 - ((Number)lastUpdated).doubleValue())));
            }
            return o;
        }
    }
    static List<Capability> parse(JSONObject device) {
        List<Capability> out = new ArrayList<>();
        JSONArray caps = device.optJSONArray("capabilities");
        if (caps == null) return out;
        for (int i = 0; i < caps.length() && out.size() < 50; i++) {
            JSONObject c = caps.optJSONObject(i); if (c == null) continue;
            int first = out.size();
            String type = c.optString("type", ""); JSONObject p = c.optJSONObject("parameters"), state = c.optJSONObject("state");
            String instance = p == null ? "" : p.optString("instance", "");
            if (instance.isEmpty() && state != null) instance = state.optString("instance", "");
            Object value = state == null ? null : primitive(state.opt("value"));
            if ("devices.capabilities.on_off".equals(type)) {
                out.add(new Capability(type, "on", "boolean", 0, 0, 0, null, value));
            } else if ("devices.capabilities.toggle".equals(type) && validInstance(instance)) {
                out.add(new Capability(type, instance, "boolean", 0, 0, 0, null, value));
            } else if ("devices.capabilities.mode".equals(type) && validInstance(instance) && p != null) {
                JSONArray modes = p.optJSONArray("modes"); List<String> valid = new ArrayList<>();
                if (modes != null) for (int j = 0; j < modes.length() && j < 100; j++) {
                    JSONObject mode = modes.optJSONObject(j); String v = mode == null ? "" : mode.optString("value", "");
                    if (validInstance(v) && !valid.contains(v)) valid.add(v);
                }
                if (!valid.isEmpty()) out.add(new Capability(type, instance, "mode", 0, 0, 0, valid, value));
            } else if ("devices.capabilities.range".equals(type) && validInstance(instance) && p != null && p.optBoolean("random_access", true)) {
                Capability cap = range(type, instance, p.optJSONObject("range"), value);
                if (cap != null) out.add(cap);
            } else if ("devices.capabilities.color_setting".equals(type) && p != null) {
                if ("rgb".equals(p.optString("color_model"))) out.add(new Capability(type, "rgb", "rgb", 0, 16777215, 1, null, null));
                Capability temp = range(type, "temperature_k", p.optJSONObject("temperature_k"), null);
                if (temp != null) out.add(temp);
                JSONObject scene = p.optJSONObject("color_scene");
                JSONArray scenes = scene == null ? null : scene.optJSONArray("scenes");
                List<String> valid = new ArrayList<>();
                if (scenes != null) for (int j = 0; j < scenes.length() && j < 100; j++) {
                    JSONObject entry = scenes.optJSONObject(j); String v = entry == null ? "" : entry.optString("id", "");
                    if (validInstance(v) && !valid.contains(v)) valid.add(v);
                }
                if (!valid.isEmpty()) out.add(new Capability(type, "scene", "mode", 0, 0, 0, valid, null));
            }
            String unit = p == null ? "" : p.optString("unit", "");
            Object updated = c.opt("last_updated");
            for (int j = first; j < out.size(); j++) {
                if (validUnit(unit)) out.get(j).unit = unit;
                if (updated instanceof Number && Double.isFinite(((Number) updated).doubleValue())) out.get(j).lastUpdated = updated;
            }
        }
        return out;
    }
    private static Capability range(String type, String instance, JSONObject r, Object state) {
        if (r == null || !(r.opt("min") instanceof Number) || !(r.opt("max") instanceof Number)) return null;
        double min = r.optDouble("min", Double.NaN), max = r.optDouble("max", Double.NaN), step = r.optDouble("precision", 1);
        if (!Double.isFinite(min) || !Double.isFinite(max) || !Double.isFinite(step) || min > max || step < 0 || Math.abs(min) > 1e9 || Math.abs(max) > 1e9) return null;
        return new Capability(type, instance, "number", min, max, step, null, state);
    }
    private static boolean validUnit(String s) { return s != null && s.matches("unit\\.[a-z0-9_.]{1,75}"); }
    private static boolean validInstance(String s) { return s != null && s.matches("[a-z0-9_]{1,80}"); }
    private static Object primitive(Object v) {
        if (v instanceof Boolean) return v;
        if (v instanceof Number && Double.isFinite(((Number) v).doubleValue())) return v;
        if (v instanceof String) { String s = (String) v; if (s.length() <= 80 && s.matches("[A-Za-z0-9_.-]+")) return s; }
        return null;
    }
    static JSONArray prompt(JSONObject device) throws Exception {
        JSONArray out = new JSONArray(); List<Capability> list = parse(device);
        for (int i = 0; i < list.size(); i++) out.put(list.get(i).prompt(i));
        return out;
    }
    static JSONArray properties(JSONObject device) throws Exception {
        JSONArray out = new JSONArray(), props = device.optJSONArray("properties");
        if (props == null) return out;
        for (int i = 0; i < props.length() && i < 50; i++) {
            JSONObject p = props.optJSONObject(i); if (p == null) continue;
            JSONObject state = p.optJSONObject("state"); if (state == null) continue;
            String type = p.optString("type", ""), instance = state.optString("instance", "");
            Object value = primitive(state.opt("value"));
            if (!type.matches("devices\\.properties\\.[a-z_]{1,40}") || !validInstance(instance) || value == null) continue;
            JSONObject o = new JSONObject().put("type", type.substring("devices.properties.".length())).put("instance", instance).put("value", value);
            JSONObject parameters = p.optJSONObject("parameters");
            if (parameters != null && validUnit(parameters.optString("unit", ""))) o.put("unit", parameters.optString("unit"));
            Object time = p.opt("last_updated");
            if (time instanceof Number && Double.isFinite(((Number) time).doubleValue())) {
                o.put("last_updated", time).put("updated_age_seconds", Math.max(0, (long)(System.currentTimeMillis() / 1000.0 - ((Number)time).doubleValue())));
            }
            out.put(o);
        }
        return out;
    }
    private HomeCapabilities() { }
}
