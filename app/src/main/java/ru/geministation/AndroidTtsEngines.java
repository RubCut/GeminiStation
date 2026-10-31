package ru.geministation;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.speech.tts.TextToSpeech;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Enumerates only enabled Android TextToSpeech services, not arbitrary apps. */
final class AndroidTtsEngines {
    static final String GEMINI = "gemini";
    static final class Entry {
        final String packageName, label;
        Entry(String packageName, String label) { this.packageName = packageName; this.label = label; }
    }
    static List<Entry> list(Context context) {
        PackageManager pm = context.getPackageManager();
        LinkedHashMap<String, Entry> entries = new LinkedHashMap<>();
        Intent intent = new Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE);
        for (ResolveInfo resolved : pm.queryIntentServices(intent, 0)) {
            ServiceInfo service = resolved.serviceInfo;
            if (service == null || service.applicationInfo == null || !service.exported || !service.enabled || !service.applicationInfo.enabled
                    || !validPackage(service.packageName) || entries.containsKey(service.packageName)) continue;
            if (service.permission != null && !service.permission.isEmpty()
                    && context.checkSelfPermission(service.permission) != PackageManager.PERMISSION_GRANTED) continue;
            CharSequence label = resolved.loadLabel(pm);
            String safe = label == null ? service.packageName : label.toString().replaceAll("[\\p{Cntrl}\\p{Cf}]", " ").trim();
            if (safe.isEmpty()) safe = service.packageName;
            if (safe.length() > 80) safe = safe.substring(0, 80);
            entries.put(service.packageName, new Entry(service.packageName, safe));
        }
        List<Entry> result = new ArrayList<>(entries.values());
        result.sort(Comparator.comparing(e -> e.label.toLowerCase(java.util.Locale.ROOT)));
        return result;
    }
    static boolean validPackage(String value) {
        return value != null && value.length() <= 200 && value.matches("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+");
    }
    static boolean available(Context context, String value) {
        if (GEMINI.equals(value)) return true;
        for (Entry entry : list(context)) if (entry.packageName.equals(value)) return true;
        return false;
    }
    static JSONArray json(Context context) throws Exception {
        JSONArray result = new JSONArray();
        result.put(new JSONObject().put("id", GEMINI).put("name", "Gemini TTS"));
        for (Entry e : list(context)) result.put(new JSONObject().put("id", e.packageName).put("name", e.label));
        return result;
    }
    private AndroidTtsEngines() { }
}
