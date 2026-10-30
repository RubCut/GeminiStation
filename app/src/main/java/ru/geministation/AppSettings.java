package ru.geministation;

import android.content.Context;

final class AppSettings {
    private static final String DEFAULT_MODEL = "gemini-3.5-flash-lite";
    // Same preferences file Activity.getPreferences used in the initial prototype.
    static String model(Context context) {
        return context.getSharedPreferences("MainActivity", Context.MODE_PRIVATE).getString("model", DEFAULT_MODEL);
    }
    static boolean validModel(String model) { return model != null && model.matches("[A-Za-z0-9._-]{1,80}"); }
    static boolean saveModel(Context context, String model) {
        return context.getSharedPreferences("MainActivity", Context.MODE_PRIVATE).edit().putString("model", model).commit();
    }
    private AppSettings() { }
}
