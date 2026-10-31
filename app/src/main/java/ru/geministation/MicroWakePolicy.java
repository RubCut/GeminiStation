package ru.geministation;

/** Experimental probability threshold, independent of PocketSphinx's KWS threshold. */
final class MicroWakePolicy {
    static final String POCKET = "pocketsphinx", MICRO = "microwakeword", MICRO_STRESS = "microwakeword_all_stress";
    static boolean isMicro(String value) { return MICRO.equals(value) || MICRO_STRESS.equals(value); }
    static boolean validEngine(String value) { return POCKET.equals(value) || isMicro(value); }
    static String modelAsset(String value) {
        if (MICRO.equals(value)) return "microwakeword/gemini_experimental.tflite";
        if (MICRO_STRESS.equals(value)) return "microwakeword/gemini_all_stress_experimental.tflite";
        throw new IllegalArgumentException("Not a microWakeWord engine");
    }
    static String normalizeEngine(String value) { return validEngine(value) ? value : POCKET; }
    static float threshold(String sensitivity) {
        int value = Integer.parseInt(WakeSensitivity.normalize(sensitivity));
        // 50% corresponds to the uncalibrated experimental 0.9 threshold.
        return value <= 50 ? .99f - (value - 1) * .09f / 49f : .90f - (value - 50) * .40f / 50f;
    }
    private MicroWakePolicy() { }
}
