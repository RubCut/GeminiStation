package ru.geministation;

import java.util.Arrays;

/** Signal guard and optional gain. This is not speech recognition; no audio is retained. */
final class WakeAudioFilter {
    private static final int RATE = 16000, HISTORY = 50, WINDOW = 16;
    private final int[] levels = new int[HISTORY], voicedSamples = new int[WINDOW], frameSamples = new int[WINDOW];
    private int levelCount, levelNext, frameNext, recentVoiced, recentTotal;
    private int sinceActive = Integer.MAX_VALUE, sinceReset;
    private double gain = 1.0, noiseFloor = 40;
    private int lastRms;

    /** Process raw samples before any gain. The ring covers at most 1.6 s for normal 100 ms reads. */
    void process(short[] samples, int count, boolean amplify) {
        if (count <= 0) return;
        long sum = 0; int crossings = 0, previous = samples[0];
        for (int i = 0; i < count; i++) {
            int value = samples[i]; sum += (long) value * value;
            if (i > 0 && ((value >= 0 && previous < 0) || (value < 0 && previous >= 0))) crossings++;
            previous = value;
        }
        lastRms = (int) Math.sqrt((double) sum / count);
        if (levelCount == 0) noiseFloor = Math.max(12, lastRms);
        levels[levelNext] = lastRms; levelNext = (levelNext + 1) % HISTORY;
        levelCount = Math.min(levelCount + 1, HISTORY);
        if (levelCount >= 5) {
            int[] sorted = Arrays.copyOf(levels, levelCount); Arrays.sort(sorted);
            int target = Math.max(12, sorted[(levelCount - 1) / 5]);
            // Learn a steady background, but do not let a short spoken word become the floor.
            noiseFloor += (target - noiseFloor) * (target > noiseFloor ? .05 : .15);
        }
        double crossingsPerSecond = crossings * (double) RATE / count;
        boolean active = lastRms >= Math.max(25, noiseFloor * 1.30)
                && crossingsPerSecond >= 70 && crossingsPerSecond <= 6000;
        recentVoiced -= voicedSamples[frameNext]; recentTotal -= frameSamples[frameNext];
        voicedSamples[frameNext] = active ? count : 0; frameSamples[frameNext] = count;
        recentVoiced += voicedSamples[frameNext]; recentTotal += count;
        frameNext = (frameNext + 1) % WINDOW;
        if (active) { sinceActive = 0; sinceReset = 0; }
        else {
            sinceActive = (int) Math.min((long) Integer.MAX_VALUE, (long) sinceActive + count);
            sinceReset = (int) Math.min((long) Integer.MAX_VALUE, (long) sinceReset + count);
        }
        double targetGain = amplify && active ? Math.min(3.5, Math.max(1.0, 1000.0 / Math.max(lastRms, 1))) : 1.0;
        gain += (targetGain - gain) * (targetGain > gain ? .32 : .12);
        if (!amplify) { gain = 1.0; return; }
        if (active && gain > 1.01) for (int i = 0; i < count; i++) {
            int boosted = (int) Math.round(samples[i] * gain);
            samples[i] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, boosted));
        }
    }
    /** Mandatory recent-signal guard, even when the optional stricter filter is disabled. */
    boolean candidateAllowed(boolean strict) {
        return recentTotal >= RATE / 5 && recentVoiced >= (strict ? RATE * 3 / 10 : RATE / 5)
                && sinceActive <= RATE * 3 / 10;
    }
    boolean speechLike() { return candidateAllowed(false); }
    /** Reset the decoder periodically in silence so stale KWS hypotheses do not accumulate. */
    boolean resetForSilence() { return sinceReset >= RATE * 4 / 5; }
    int rms() { return lastRms; }
    int floor() { return (int) Math.round(noiseFloor); }
    int gainPercent() { return (int) Math.round(gain * 100); }
    int activeMillis() { return (int) (recentVoiced * 1000L / RATE); }
    int quietMillis() { return sinceActive == Integer.MAX_VALUE ? -1 : (int) (sinceActive * 1000L / RATE); }
    void clearSpeech() {
        Arrays.fill(voicedSamples, 0); Arrays.fill(frameSamples, 0);
        recentVoiced = recentTotal = frameNext = sinceReset = 0;
        sinceActive = Integer.MAX_VALUE;
    }
}
