package ru.geministation;

import java.util.Arrays;

/** Lightweight signal conditioning, not a speech recognizer. Raw audio is never retained. */
final class WakeAudioFilter {
    private static final int RATE = 16000, HISTORY = 30, WINDOW = 24;
    private final int[] levels = new int[HISTORY], voicedSamples = new int[WINDOW], frameSamples = new int[WINDOW];
    private int levelCount, levelNext, frameNext, recentVoiced, recentTotal;
    private double gain = 1.0;
    private int floor = 40, lastRms;

    /** Returns a bounded, in-place amplified copy of the frame when requested. */
    void process(short[] samples, int count, boolean amplify) {
        if (count <= 0) return;
        long sum = 0; int crossings = 0, previous = samples[0];
        for (int i = 0; i < count; i++) {
            int value = samples[i]; sum += (long) value * value;
            if (i > 0 && ((value >= 0 && previous < 0) || (value < 0 && previous >= 0))) crossings++;
            previous = value;
        }
        lastRms = (int) Math.sqrt((double) sum / count);
        levels[levelNext] = lastRms; levelNext = (levelNext + 1) % HISTORY;
        levelCount = Math.min(levelCount + 1, HISTORY);
        if (levelCount >= 5) {
            int[] sorted = Arrays.copyOf(levels, levelCount); Arrays.sort(sorted);
            floor = Math.max(12, sorted[(levelCount - 1) / 5]);
        }
        // Reject isolated impulses and DC while keeping quiet, sustained speech eligible.
        double crossingsPerSecond = crossings * (double) RATE / count;
        boolean active = lastRms >= Math.max(25, floor * 1.30)
                && crossingsPerSecond >= 70 && crossingsPerSecond <= 11000;
        recentVoiced -= voicedSamples[frameNext]; recentTotal -= frameSamples[frameNext];
        voicedSamples[frameNext] = active ? count : 0; frameSamples[frameNext] = count;
        recentVoiced += voicedSamples[frameNext]; recentTotal += count;
        frameNext = (frameNext + 1) % WINDOW;
        // Amplitude alone cannot recover speech masked by noise. Limit amplification
        // and never increase gain for frames below the estimated noise floor.
        double target = amplify && active ? Math.min(3.5, Math.max(1.0, 1000.0 / Math.max(lastRms, 1))) : 1.0;
        gain += (target - gain) * (target > gain ? .32 : .12);
        if (!amplify) { gain = 1.0; return; }
        if (active && gain > 1.01) for (int i = 0; i < count; i++) {
            int boosted = (int) Math.round(samples[i] * gain);
            samples[i] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, boosted));
        }
    }
    /** At least 200 ms of voice-like signal across the last approximately 2.4 s. */
    boolean speechLike() { return recentTotal >= RATE / 5 && recentVoiced >= RATE / 5; }
    int rms() { return lastRms; }
    int floor() { return floor; }
    int gainPercent() { return (int) Math.round(gain * 100); }
    void clearSpeech() { Arrays.fill(voicedSamples, 0); Arrays.fill(frameSamples, 0); recentVoiced = recentTotal = frameNext = 0; }
}
