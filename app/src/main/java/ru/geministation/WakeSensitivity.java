package ru.geministation;

/** Smaller PocketSphinx keyword thresholds are more sensitive, but cause more false alarms. */
final class WakeSensitivity {
    static final String DEFAULT = "normal";
    static boolean valid(String value) { return "low".equals(value) || "normal".equals(value) || "high".equals(value); }
    static String threshold(String value) {
        if ("low".equals(value)) return "1e-15";
        if ("high".equals(value)) return "1e-35";
        return "1e-25";
    }
    static String keywords(String value) {
        String t = threshold(value);
        StringBuilder keywords = new StringBuilder();
        for (String word : WakeWordResult.KEYWORDS) keywords.append(word).append(" /").append(t).append("/\n");
        return keywords.toString();
    }
    private WakeSensitivity() { }
}
