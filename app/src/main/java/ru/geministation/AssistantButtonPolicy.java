package ru.geministation;

/** Tracks one voice-assist key press; no device-id or scan-code binding. */
final class AssistantButtonPolicy {
    static final int VOICE_ASSIST = 231;
    static final int DOWN = 0;
    static final int UP = 1;
    enum Decision { PASS, TRIGGER, CONSUME }
    private boolean pressed;
    Decision key(int code, int action, int repeat, boolean enabled) {
        if (code != VOICE_ASSIST) return Decision.PASS;
        if (action == DOWN) {
            if (pressed) return Decision.CONSUME;
            if (repeat != 0 || !enabled) return Decision.PASS;
            pressed = true;
            return Decision.TRIGGER;
        }
        if (action == UP && pressed) {
            pressed = false;
            return Decision.CONSUME;
        }
        return Decision.PASS;
    }
    void reset() { pressed = false; }
}
