package ru.geministation;

import android.content.Context;

final class AppSettings {
    private static final String DEFAULT_MODEL = "gemini-3.5-flash-lite";
    // Same preferences file Activity.getPreferences used in the initial prototype.
    static String model(Context context) {
        return context.getSharedPreferences("MainActivity", Context.MODE_PRIVATE).getString("model", DEFAULT_MODEL);
    }
    static boolean validModel(String model) { return model != null && model.matches("[A-Za-z0-9._-]{1,80}"); }
    static String voice(Context context) {
        String stored = context.getSharedPreferences("MainActivity", Context.MODE_PRIVATE).getString("voice", TtsVoices.DEFAULT);
        return TtsVoices.orDefault(stored);
    }
    static String ttsEngine(Context context) {
        String engine = context.getSharedPreferences("MainActivity", Context.MODE_PRIVATE).getString("tts_engine", AndroidTtsEngines.GEMINI);
        return AndroidTtsEngines.GEMINI.equals(engine) || AndroidTtsEngines.validPackage(engine) ? engine : AndroidTtsEngines.GEMINI;
    }
    static boolean saveTtsEngine(Context context, String engine) {
        return AndroidTtsEngines.available(context, engine) && context.getSharedPreferences("MainActivity", Context.MODE_PRIVATE).edit()
                .putString("tts_engine", engine).commit();
    }
    static boolean buttonEnabled(Context context) {
        return context.getSharedPreferences("MainActivity", Context.MODE_PRIVATE).getBoolean("button_enabled", false);
    }
    static boolean wakeEnabled(Context context) {
        return context.getSharedPreferences("MainActivity", Context.MODE_PRIVATE).getBoolean("wake_enabled", false);
    }
    static String wakeSensitivity(Context context) {
        String value = context.getSharedPreferences("MainActivity", Context.MODE_PRIVATE).getString("wake_sensitivity", WakeSensitivity.DEFAULT);
        return WakeSensitivity.normalize(value);
    }
    static String wakeEngine(Context context) {
        String value = context.getSharedPreferences("MainActivity", Context.MODE_PRIVATE).getString("wake_engine", MicroWakePolicy.DEFAULT);
        return MicroWakePolicy.normalizeEngine(value);
    }
    static boolean wakeFilter(Context context) {
        return context.getSharedPreferences("MainActivity", Context.MODE_PRIVATE).getBoolean("wake_filter", true);
    }
    static boolean wakePersonal(Context context) {
        return context.getSharedPreferences("MainActivity", Context.MODE_PRIVATE).getBoolean("wake_personal", true);
    }
    static boolean wakeVad(Context context) {
        return context.getSharedPreferences("MainActivity", Context.MODE_PRIVATE).getBoolean("wake_vad", true);
    }
    static boolean wakeAmplify(Context context) {
        return context.getSharedPreferences("MainActivity", Context.MODE_PRIVATE).getBoolean("wake_amplify", true);
    }
    static boolean audioDuckEnabled(Context context) {
        return context.getSharedPreferences("MainActivity", Context.MODE_PRIVATE).getBoolean("audio_duck", true);
    }
    static boolean save(Context context, String model, String voice, boolean buttonEnabled, boolean wakeEnabled, String wakeSensitivity, boolean audioDuckEnabled) {
        return save(context, model, voice, buttonEnabled, wakeEnabled, wakeSensitivity, audioDuckEnabled, wakeFilter(context), wakeAmplify(context));
    }
    static boolean save(Context context, String model, String voice, boolean buttonEnabled, boolean wakeEnabled, String wakeSensitivity, boolean audioDuckEnabled, boolean wakeFilter, boolean wakeAmplify) {
        return save(context, model, voice, buttonEnabled, wakeEnabled, wakeSensitivity, audioDuckEnabled, wakeFilter, wakeAmplify, wakeEngine(context));
    }
    static boolean save(Context context, String model, String voice, boolean buttonEnabled, boolean wakeEnabled, String wakeSensitivity, boolean audioDuckEnabled, boolean wakeFilter, boolean wakeAmplify, String wakeEngine) {
        return save(context, model, voice, buttonEnabled, wakeEnabled, wakeSensitivity, audioDuckEnabled, wakeFilter, wakeAmplify, wakeEngine, wakeVad(context));
    }
    static boolean save(Context context, String model, String voice, boolean buttonEnabled, boolean wakeEnabled, String wakeSensitivity, boolean audioDuckEnabled, boolean wakeFilter, boolean wakeAmplify, String wakeEngine, boolean wakeVad) {
        return save(context, model, voice, buttonEnabled, wakeEnabled, wakeSensitivity, audioDuckEnabled, wakeFilter, wakeAmplify, wakeEngine, wakeVad, wakePersonal(context));
    }
    static boolean save(Context context, String model, String voice, boolean buttonEnabled, boolean wakeEnabled, String wakeSensitivity, boolean audioDuckEnabled, boolean wakeFilter, boolean wakeAmplify, String wakeEngine, boolean wakeVad, boolean wakePersonal) {
        if (!validModel(model) || !TtsVoices.valid(voice) || !WakeSensitivity.valid(wakeSensitivity) || !MicroWakePolicy.validEngine(wakeEngine)) return false;
        return context.getSharedPreferences("MainActivity", Context.MODE_PRIVATE).edit()
                .putString("model", model).putString("voice", voice).putBoolean("button_enabled", buttonEnabled)
                .putBoolean("wake_enabled", wakeEnabled).putString("wake_sensitivity", WakeSensitivity.normalize(wakeSensitivity))
                .putBoolean("audio_duck", audioDuckEnabled).putBoolean("wake_filter", wakeFilter)
                .putBoolean("wake_amplify", wakeAmplify).putString("wake_engine", wakeEngine)
                .putBoolean("wake_vad", wakeVad).putBoolean("wake_personal", wakePersonal).commit();
    }
    static String systemPrompt(Context context) {
        return context.getSharedPreferences("MainActivity", Context.MODE_PRIVATE).getString("system_prompt", SystemPrompt.DEFAULT);
    }
    static boolean saveSystemPrompt(Context context, String prompt) {
        return SystemPrompt.valid(prompt) && context.getSharedPreferences("MainActivity", Context.MODE_PRIVATE).edit()
                .putString("system_prompt", prompt).commit();
    }
    static boolean dnsEnabled(Context context) {
        return context.getSharedPreferences("MainActivity", Context.MODE_PRIVATE).getBoolean("dns_enabled", true);
    }
    static boolean dnsBypassVpn(Context context) {
        return context.getSharedPreferences("MainActivity", Context.MODE_PRIVATE).getBoolean("dns_bypass_vpn", true);
    }
    static String dnsServer(Context context) {
        return context.getSharedPreferences("MainActivity", Context.MODE_PRIVATE).getString("dns_server", DnsSettings.DEFAULT_SERVER);
    }
    static boolean saveDns(Context context, boolean enabled, boolean bypassVpn, String server) {
        if (!DnsSettings.validServer(server)) return false;
        return context.getSharedPreferences("MainActivity", Context.MODE_PRIVATE).edit()
                .putBoolean("dns_enabled", enabled).putBoolean("dns_bypass_vpn", bypassVpn)
                .putString("dns_server", server).commit();
    }
    private AppSettings() { }
}
