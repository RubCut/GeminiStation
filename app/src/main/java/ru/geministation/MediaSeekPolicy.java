package ru.geministation;

/** Bounded timestamps in milliseconds; no Android dependency, so all arithmetic is unit-tested. */
final class MediaSeekPolicy {
    static final int MAX_SECONDS = 7 * 24 * 60 * 60;
    static boolean valid(String mode, int seconds) {
        return ("absolute".equals(mode) || "forward".equals(mode) || "backward".equals(mode))
                && seconds >= 0 && seconds <= MAX_SECONDS
                && (seconds > 0 || "absolute".equals(mode));
    }
    /** -1 = unavailable/out of range; duration < 0 means unknown. Relative seeks clamp to media ends. */
    static long target(String mode, int seconds, long current, long duration) {
        if (!valid(mode, seconds)) return -1;
        long delta = seconds * 1000L;
        if ("absolute".equals(mode)) return duration >= 0 && delta > duration ? -1 : delta;
        if (current < 0 || ("forward".equals(mode) && current > Long.MAX_VALUE - delta)) return -1;
        long result = "forward".equals(mode) ? current + delta : Math.max(0L, current - delta);
        return duration >= 0 ? Math.min(result, duration) : result;
    }
    private MediaSeekPolicy() { }
}
