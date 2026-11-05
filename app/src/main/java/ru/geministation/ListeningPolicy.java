package ru.geministation;

/** Silence timeout starts after the microphone is ready, not while the network connects. */
final class ListeningPolicy {
    static final long NO_SPEECH_MS = 5000;
    static final long SIGNAL_GRACE_MS = 1000;
    private boolean graceUsed;
    void reset() { graceUsed = false; }
    boolean grantGrace(boolean recentSignal) {
        if (!recentSignal || graceUsed) return false;
        graceUsed = true;
        return true;
    }
}
