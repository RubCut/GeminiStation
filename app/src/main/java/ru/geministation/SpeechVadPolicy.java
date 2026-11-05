package ru.geministation;

/** Recent speech scores aligned to the wake model's 30 ms streaming steps.
 * A delayed wake score can follow the utterance; a 1.2 s lookback avoids
 * requiring speech to still be present at the exact score peak. */
final class SpeechVadPolicy {
    static final float CUTOFF = .20f;
    static final int WINDOW = 40;
    private final float[] history = new float[WINDOW];
    private int next;
    private float intervalPeak;

    void add(float probability) {
        history[next] = probability;
        next = (next + 1) % WINDOW;
        intervalPeak = Math.max(intervalPeak, probability);
    }
    float recentPeak() {
        float peak = 0;
        for (float score : history) peak = Math.max(peak, score);
        return peak;
    }
    boolean speechRecently() { return recentPeak() >= CUTOFF; }
    float takeIntervalPeak() { float value = intervalPeak; intervalPeak = 0; return value; }
}
