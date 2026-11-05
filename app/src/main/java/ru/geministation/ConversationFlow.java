package ru.geministation;

/** UI-thread conversation transitions; a finished session can never restart itself. */
final class ConversationFlow {
    enum Phase { NEW, LISTENING, THINKING, SPEAKING, FOLLOW_UP, FINISHED }
    private Phase phase = Phase.NEW;
    Phase phase() { return phase; }
    boolean initialListening() {
        if (phase != Phase.NEW) return false;
        phase = Phase.LISTENING; return true;
    }
    boolean recognized() {
        if (phase != Phase.LISTENING && phase != Phase.FOLLOW_UP) return false;
        phase = Phase.THINKING; return true;
    }
    boolean answered() {
        if (phase != Phase.THINKING) return false;
        phase = Phase.SPEAKING; return true;
    }
    boolean followUp() {
        if (phase != Phase.SPEAKING) return false;
        phase = Phase.FOLLOW_UP; return true;
    }
    void finish() { phase = Phase.FINISHED; }
}
