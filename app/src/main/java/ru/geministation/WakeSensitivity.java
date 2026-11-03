package ru.geministation;

/** Integer percentage and legacy named settings for microWakeWord. */
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
    private WakeSensitivity() { }
}
