package ru.geministation;

/** Two local streaming microWakeWord models; legacy engine preferences migrate to the new model. */
final class MicroWakePolicy {
    static final String MICRO = "microwakeword", SELECTED_TTS = "microwakeword_selected_tts", DEFAULT = SELECTED_TTS;
    static boolean validEngine(String value) { return MICRO.equals(value) || SELECTED_TTS.equals(value); }
    static String modelAsset(String value) {
        if (MICRO.equals(value)) return "microwakeword/gemini_experimental.tflite";
        if (SELECTED_TTS.equals(value)) return "microwakeword/gemini_selected_tts_experimental.tflite";
        throw new IllegalArgumentException("Not a microWakeWord engine");
    }
    // Old PocketSphinx and all-stress IDs are no longer selectable. Stored IDs
    // migrate here without enabling wake listening or changing sensitivity.
    static String normalizeEngine(String value) { return validEngine(value) ? value : DEFAULT; }
    static float threshold(String sensitivity) {
        int value = Integer.parseInt(WakeSensitivity.normalize(sensitivity));
        // Same uncalibrated scale for both models, pending device measurements.
        return value <= 50 ? .99f - (value - 1) * .09f / 49f : .90f - (value - 50) * .40f / 50f;
    }
    private MicroWakePolicy() { }
}
