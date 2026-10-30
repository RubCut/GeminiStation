package ru.geministation;

import java.util.Locale;

/** Percentages interpolate logarithmically between the existing PocketSphinx thresholds. */
final class WakeSensitivity {
    static final String DEFAULT = "50";

    /** Legacy API clients and already-saved preferences remain supported. */
    static boolean valid(String value) {
        return "low".equals(value) || "normal".equals(value) || "high".equals(value)
                || (value != null && value.matches("(?:[1-9]|[1-9][0-9]|100)"));
    }
    static String normalize(String value) {
        if ("low".equals(value)) return "1";
        if ("normal".equals(value)) return "50";
        if ("high".equals(value)) return "100";
        return valid(value) ? value : DEFAULT;
    }
    static String threshold(String value) {
        int percent = Integer.parseInt(normalize(value));
        // Keep all three old thresholds exactly, notably the user's tested high setting.
        if (percent == 1) return "1e-15";
        if (percent == 50) return "1e-25";
        if (percent == 100) return "1e-35";
        double exponent = percent < 50
                ? 15.0 + (percent - 1) * 10.0 / 49.0
                : 25.0 + (percent - 50) * 10.0 / 50.0;
        return String.format(Locale.ROOT, "%.12e", Math.pow(10.0, -exponent));
    }
    static String keywords(String value) {
        String t = threshold(value);
        StringBuilder keywords = new StringBuilder();
        for (String word : WakeWordResult.KEYWORDS) keywords.append(word).append(" /").append(t).append("/\n");
        return keywords.toString();
    }
    private WakeSensitivity() { }
}
