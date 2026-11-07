package ru.geministation;

/** Worker-owned, bounded retry schedule for unexpectedly terminated microphone capture. */
final class WakeRetry {
    private int failures;
    long afterFailure(long lifetimeMs) {
        if (lifetimeMs >= 60000) failures = 0;
        return failures >= 3 ? -1 : 3000L << failures++;
    }
    void reset() { failures = 0; }
}
