package ru.geministation;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONObject;

/** Local signal and Live timing settings. Existing installations receive these defaults. */
final class VoiceTuning {
    final int wakeMinMs, wakeRecentMs, wakeResetMs, silenceMs, finalMs, interimMs;
    final boolean preconnect, earlyCapture;
    VoiceTuning(int wakeMinMs, int wakeRecentMs, int wakeResetMs, int silenceMs, int finalMs, int interimMs, boolean preconnect, boolean earlyCapture) {
        this.wakeMinMs = wakeMinMs; this.wakeRecentMs = wakeRecentMs; this.wakeResetMs = wakeResetMs;
        this.silenceMs = silenceMs; this.finalMs = finalMs; this.interimMs = interimMs; this.preconnect = preconnect; this.earlyCapture = earlyCapture;
    }
    private static boolean range(int value, int min, int max) { return value >= min && value <= max; }
    boolean valid() {
        return range(wakeMinMs, 100, 600) && range(wakeRecentMs, 200, 900) && range(wakeResetMs, 700, 3000)
                && range(silenceMs, 2000, 15000) && range(finalMs, 400, 3000) && range(interimMs, 800, 5000);
    }
    static VoiceTuning defaults() { return new VoiceTuning(200, 400, 1200, 5000, 800, 1800, true, true); }
    static VoiceTuning load(Context context) {
        SharedPreferences p = context.getSharedPreferences("voice_tuning", Context.MODE_PRIVATE);
        VoiceTuning d = defaults();
        VoiceTuning value = new VoiceTuning(p.getInt("wakeMinMs", d.wakeMinMs), p.getInt("wakeRecentMs", d.wakeRecentMs),
                p.getInt("wakeResetMs", d.wakeResetMs), p.getInt("silenceMs", d.silenceMs),
                p.getInt("finalMs", d.finalMs), p.getInt("interimMs", d.interimMs), p.getBoolean("preconnect", d.preconnect), p.getBoolean("earlyCapture", d.earlyCapture));
        return value.valid() ? value : d;
    }
    boolean save(Context context) {
        return valid() && context.getSharedPreferences("voice_tuning", Context.MODE_PRIVATE).edit()
                .putInt("wakeMinMs", wakeMinMs).putInt("wakeRecentMs", wakeRecentMs).putInt("wakeResetMs", wakeResetMs)
                .putInt("silenceMs", silenceMs).putInt("finalMs", finalMs).putInt("interimMs", interimMs)
                .putBoolean("preconnect", preconnect).putBoolean("earlyCapture", earlyCapture).commit();
    }
    JSONObject json() throws Exception {
        return new JSONObject().put("wakeMinMs", wakeMinMs).put("wakeRecentMs", wakeRecentMs).put("wakeResetMs", wakeResetMs)
                .put("silenceMs", silenceMs).put("finalMs", finalMs).put("interimMs", interimMs).put("preconnect", preconnect).put("earlyCapture", earlyCapture);
    }
    static VoiceTuning parse(JSONObject data) throws Exception {
        String[] keys = {"wakeMinMs", "wakeRecentMs", "wakeResetMs", "silenceMs", "finalMs", "interimMs"};
        for (String key : keys) if (!(data.opt(key) instanceof Integer)) throw new IllegalArgumentException("Ожидается целое число: " + key);
        if (!(data.opt("preconnect") instanceof Boolean) || !(data.opt("earlyCapture") instanceof Boolean)) throw new IllegalArgumentException("Некорректный режим подключения");
        VoiceTuning value = new VoiceTuning(data.getInt(keys[0]), data.getInt(keys[1]), data.getInt(keys[2]),
                data.getInt(keys[3]), data.getInt(keys[4]), data.getInt(keys[5]), data.getBoolean("preconnect"), data.getBoolean("earlyCapture"));
        if (!value.valid()) throw new IllegalArgumentException("Параметры распознавания вне допустимых границ");
        return value;
    }
    boolean sameWake(VoiceTuning other) {
        return other != null && wakeMinMs == other.wakeMinMs && wakeRecentMs == other.wakeRecentMs && wakeResetMs == other.wakeResetMs;
    }
}
