package ru.geministation;

/** Aggregate diagnostic only; samples are never retained or written to disk. */
final class AudioLevel {
    private long squares, count;
    private int peak;
    void add(short[] samples, int length) {
        for (int i = 0; i < length; i++) {
            int sample = samples[i];
            peak = Math.max(peak, Math.abs(sample));
            squares += (long) sample * sample;
        }
        count += length;
    }
    int rms() { return count == 0 ? 0 : (int) Math.sqrt((double) squares / count); }
    int peak() { return peak; }
    void reset() { squares = 0; count = 0; peak = 0; }
}
