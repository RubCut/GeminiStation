package ru.geministation.buttonhelper;

/** Only an explicit denied status triggers a fixed grant. Unknown never grants. */
final class MediaGuardPolicy {
    enum Access { GRANTED, DENIED, UNKNOWN }
    private int attempts;
    private long nextAttempt;

    static Access parse(String value) {
        if ("granted".equals(value)) return Access.GRANTED;
        if ("denied".equals(value)) return Access.DENIED;
        return Access.UNKNOWN;
    }
    boolean shouldGrant(Access access, long now) {
        if (access == Access.GRANTED) { attempts = 0; nextAttempt = 0; return false; }
        if (access != Access.DENIED || now < nextAttempt) return false;
        long delay = Math.min(60_000L, 3_000L << Math.min(attempts, 5));
        attempts++;
        nextAttempt = now + delay;
        return true;
    }
}
