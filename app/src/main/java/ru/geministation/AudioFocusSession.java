package ru.geministation;

/** UI-thread ownership of a transient focus request. */
final class AudioFocusSession {
    interface Backend {
        boolean request();
        void abandon();
    }
    private final Backend backend;
    private boolean held;
    AudioFocusSession(Backend backend) { this.backend = backend; }
    boolean acquire() {
        if (held) return true;
        held = backend.request();
        return held;
    }
    boolean isHeld() { return held; }
    void release() {
        if (!held) return;
        held = false;
        backend.abandon();
    }
    // A permanent loss is removed from the focus stack by Android. Transient loss stays owned.
    void permanentlyLost() { held = false; }
}
