package ru.geministation;

/** Experimental probability threshold, independent of PocketSphinx's KWS threshold. */
final class MicroWakePolicy {
    static final String POCKET = "pocketsphinx", MICRO = "microwakeword";
    static boolean validEngine(String value) { return POCKET.equals(value) || MICRO.equals(value); }
    static String normalizeEngine(String value) { return validEngine(value) ? value : POCKET; }
    static float threshold(String sensitivity) {
        int value = Integer.parseInt(WakeSensitivity.normalize(sensitivity));
        // 50% corresponds to the uncalibrated experimental 0.9 threshold.
        return value <= 50 ? .99f - (value - 1) * .09f / 49f : .90f - (value - 50) * .40f / 50f;
    }
    private MicroWakePolicy() { }
}
