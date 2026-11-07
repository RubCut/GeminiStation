package ru.geministation;

import android.content.Context;
import org.json.JSONObject;

/** Versioned allowlist: no credentials, voice samples, plugins or privileged device modes. */
final class SettingsBackup {
    static JSONObject export(Context c) throws Exception {
        JSONObject main = new JSONObject().put("model", AppSettings.model(c)).put("voice", AppSettings.voice(c))
                .put("ttsEngine", AppSettings.ttsEngine(c)).put("buttonEnabled", AppSettings.buttonEnabled(c))
                .put("wakeEnabled", AppSettings.wakeEnabled(c)).put("wakeSensitivity", AppSettings.wakeSensitivity(c))
                .put("wakeEngine", AppSettings.wakeEngine(c)).put("wakeFilter", AppSettings.wakeFilter(c))
                .put("wakePersonal", AppSettings.wakePersonal(c)).put("wakeVad", AppSettings.wakeVad(c))
                .put("wakeAmplify", AppSettings.wakeAmplify(c)).put("audioDuckEnabled", AppSettings.audioDuckEnabled(c))
                .put("dnsEnabled", AppSettings.dnsEnabled(c)).put("dnsBypassVpn", AppSettings.dnsBypassVpn(c))
                .put("dnsServer", AppSettings.dnsServer(c)).put("systemPrompt", AppSettings.systemPrompt(c));
        return new JSONObject().put("format", "GeminiStationSettings").put("version", 1)
                .put("settings", main).put("voiceTuning", VoiceTuning.load(c).json());
    }
    private static String string(JSONObject data, String key) throws Exception {
        if (!(data.opt(key) instanceof String)) throw new IllegalArgumentException("Некорректное поле " + key);
        return data.getString(key);
    }
    private static boolean bool(JSONObject data, String key) throws Exception {
        if (!(data.opt(key) instanceof Boolean)) throw new IllegalArgumentException("Некорректное поле " + key);
        return data.getBoolean(key);
    }
    static boolean restore(Context c, JSONObject backup) throws Exception {
        if (!"GeminiStationSettings".equals(backup.optString("format")) || !(backup.opt("version") instanceof Integer) || backup.getInt("version") != 1)
            throw new IllegalArgumentException("Неподдерживаемый формат файла");
        JSONObject p = backup.getJSONObject("settings"); VoiceTuning tuning = VoiceTuning.parse(backup.getJSONObject("voiceTuning"));
        String model = string(p,"model"), voice = string(p,"voice"), engine = string(p,"ttsEngine"), wakeEngine = string(p,"wakeEngine"), sensitivity = string(p,"wakeSensitivity"), dns = string(p,"dnsServer"), prompt = string(p,"systemPrompt");
        boolean button = bool(p,"buttonEnabled"), wake = bool(p,"wakeEnabled"), filter = bool(p,"wakeFilter"), personal = bool(p,"wakePersonal"), vad = bool(p,"wakeVad"), amplify = bool(p,"wakeAmplify"), duck = bool(p,"audioDuckEnabled"), dnsEnabled = bool(p,"dnsEnabled"), bypass = bool(p,"dnsBypassVpn");
        if (!AppSettings.validModel(model) || !TtsVoices.valid(voice) || !AndroidTtsEngines.available(c, engine)
                || !MicroWakePolicy.validEngine(wakeEngine) || !WakeSensitivity.valid(sensitivity)
                || !DnsSettings.validServer(dns) || !SystemPrompt.valid(prompt))
            throw new IllegalArgumentException("Недопустимые настройки или TTS-движок не установлен");
        // All fields are validated first; an I/O failure between preference files can still be partial.
        try {
            return AppSettings.save(c, model, voice, button, wake, sensitivity, duck, filter, amplify, wakeEngine, vad, personal)
                    && AppSettings.saveTtsEngine(c, engine) && AppSettings.saveDns(c, dnsEnabled, bypass, dns)
                    && AppSettings.saveSystemPrompt(c, prompt) && tuning.save(c);
        } finally { WakeWordController.get(c).settingsChanged(); }
    }
    private SettingsBackup() { }
}
