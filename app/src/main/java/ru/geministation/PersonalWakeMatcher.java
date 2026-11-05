package ru.geministation;

import java.util.ArrayList;
import java.util.List;

/** Small experimental query-by-example matcher. All input is 20 ms spectral features.
 * It is not a speaker identity check and is deliberately separate from microWakeWord. */
final class PersonalWakeMatcher {
    static final int BANDS = 20, WINDOW = 80, MAX_TEMPLATES = 5;
    static final float CUTOFF = .50f;
    private final List<float[][]> templates;
    private final float[][] history = new float[WINDOW][BANDS];
    private int frameCount, next, count;

    PersonalWakeMatcher(List<float[][]> templates) {
        this.templates = new ArrayList<>(templates);
    }
    static float[] normalize(float[] features) {
        if (features.length != 40) throw new IllegalArgumentException("40 features required");
        float[] result = new float[BANDS]; float mean = 0;
        for (int i = 0; i < BANDS; i++) { result[i] = (features[i * 2] + features[i * 2 + 1]) / 2; mean += result[i]; }
        mean /= BANDS; float sum = 0;
        for (int i = 0; i < BANDS; i++) { result[i] -= mean; sum += result[i] * result[i]; }
        float scale = (float) Math.sqrt(sum / BANDS) + .001f;
        for (int i = 0; i < BANDS; i++) result[i] /= scale;
        return result;
    }
    void add(float[] features) {
        // The native frontend emits 10 ms frames; keep every other frame.
        if ((frameCount++ & 1) != 0) return;
        history[next] = normalize(features);
        next = (next + 1) % WINDOW; count = Math.min(WINDOW, count + 1);
    }
    float[][] snapshot() {
        float[][] result = new float[count][];
        int start = (next - count + WINDOW) % WINDOW;
        for (int i = 0; i < count; i++) result[i] = history[(start + i) % WINDOW].clone();
        return result;
    }
    float best(float[][] recording) {
        float best = Float.POSITIVE_INFINITY;
        for (float[][] template : templates) best = Math.min(best, distance(template, recording));
        return best;
    }
    /** Subsequence dynamic time warping with a bound on matched duration. */
    static float distance(float[][] reference, float[][] audio) {
        int n = reference.length, m = audio.length;
        if (n < 10 || n > 65 || m < 10 || m > WINDOW) return Float.POSITIVE_INFINITY;
        float[] prev = new float[m + 1], cur = new float[m + 1];
        int[] starts = new int[m + 1], nextStarts = new int[m + 1];
        for (int j = 0; j <= m; j++) starts[j] = j;
        for (int i = 1; i <= n; i++) {
            cur[0] = Float.POSITIVE_INFINITY; nextStarts[0] = 0;
            for (int j = 1; j <= m; j++) {
                float v = prev[j - 1]; int start = starts[j - 1];
                if (prev[j] < v) { v = prev[j]; start = starts[j]; }
                if (cur[j - 1] < v) { v = cur[j - 1]; start = nextStarts[j - 1]; }
                float cost = 0;
                for (int k = 0; k < BANDS; k++) { float diff = reference[i - 1][k] - audio[j - 1][k]; cost += diff * diff; }
                cur[j] = v + cost / BANDS; nextStarts[j] = start;
            }
            float[] swap = prev; prev = cur; cur = swap;
            int[] swapStart = starts; starts = nextStarts; nextStarts = swapStart;
        }
        float best = Float.POSITIVE_INFINITY;
        for (int j = 1; j <= m; j++) {
            int duration = j - starts[j];
            if (duration * 100 < n * 65 || duration * 100 > n * 150) continue;
            best = Math.min(best, prev[j] / Math.max(n, duration));
        }
        return best;
    }
}
