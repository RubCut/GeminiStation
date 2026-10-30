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
    static boolean buttonEnabled(Context context) {
        return context.getSharedPreferences("MainActivity", Context.MODE_PRIVATE).getBoolean("button_enabled", false);
    }
    static boolean wakeEnabled(Context context) {
        return context.getSharedPreferences("MainActivity", Context.MODE_PRIVATE).getBoolean("wake_enabled", false);
    }
    static boolean save(Context context, String model, String voice, boolean buttonEnabled, boolean wakeEnabled) {
        if (!validModel(model) || !TtsVoices.valid(voice)) return false;
        return context.getSharedPreferences("MainActivity", Context.MODE_PRIVATE).edit()
                .putString("model", model).putString("voice", voice).putBoolean("button_enabled", buttonEnabled)
                .putBoolean("wake_enabled", wakeEnabled).commit();
    }
    private AppSettings() { }
}
