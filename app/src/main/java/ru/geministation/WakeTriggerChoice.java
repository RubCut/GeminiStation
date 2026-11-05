package ru.geministation;

/** Briefly prefer an in-flight personal result without disabling the general model. */
final class WakeTriggerChoice {
    enum Source { NONE, PERSONAL, GENERAL }
    static final long MAX_WAIT_MS = 200;
    private boolean waiting;
    private long deadline;

    boolean waiting() { return waiting; }

    Source choose(long now, boolean personalHit, boolean personalAllowed,
                  boolean generalHit, boolean generalAllowed, boolean personalPending) {
        if (personalHit && personalAllowed) { waiting = false; return Source.PERSONAL; }
        if (generalHit && generalAllowed && !waiting) {
            if (!personalPending) return Source.GENERAL;
            // The general hit has passed its guard. Do not extend its deadline for new hits.
            waiting = true;
            deadline = now + MAX_WAIT_MS;
        }
        if (waiting && (!personalPending || now >= deadline)) { waiting = false; return Source.GENERAL; }
        return Source.NONE;
    }
}
