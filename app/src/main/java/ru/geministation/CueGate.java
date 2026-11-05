package ru.geministation;

/** Single-threaded completion gate: a cancelled or replaced cue must never resume a dialog. */
final class CueGate {
    private int generation;
    private Runnable continuation;

    int begin(Runnable next) {
        generation++;
        continuation = next;
        return generation;
    }

    void finish(int token) {
        if (token != generation || continuation == null) return;
        Runnable next = continuation;
        continuation = null;
        next.run();
    }

    void cancel() {
        generation++;
        continuation = null;
    }
}
