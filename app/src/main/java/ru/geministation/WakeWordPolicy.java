package ru.geministation;

/** Pure policy: a user opt-in and service are required; the dialog owns the microphone. */
final class WakeWordPolicy {
    static boolean shouldListen(boolean serviceRunning, boolean enabled, boolean dialogActive, boolean microphoneGranted) {
        return serviceRunning && enabled && !dialogActive && microphoneGranted;
    }
    private WakeWordPolicy() { }
}
