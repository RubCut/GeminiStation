package ru.geministation;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.BooleanSupplier;

/** In-memory preference shared across calls; each attempt visits each snapshot key at most once. */
final class KeyRotation {
    static final class Entry {
        final String id, secret;
        Entry(String id, String secret) { this.id = id; this.secret = secret; }
    }
    static final class RateLimit extends IOException {
        final boolean retrySafe;
        RateLimit(String stage) { this(stage, true); }
        RateLimit(String stage, boolean retrySafe) { super(stage + ": HTTP 429"); this.retrySafe = retrySafe; }
    }
    interface Operation<T> { T run(String key) throws Exception; }
    private String preferredId;
    private final Runnable onSwitch;
    KeyRotation(Runnable onSwitch) { this.onSwitch = onSwitch; }

    synchronized Attempt attempt(List<Entry> keys) {
        List<Entry> ordered = new ArrayList<>(keys);
        int start = -1;
        for (int i = 0; i < ordered.size(); i++) if (ordered.get(i).id.equals(preferredId)) { start = i; break; }
        // A key can be removed while this process is alive. Forget stale preferences.
        if (start < 0) {
            start = 0;
            preferredId = ordered.isEmpty() ? null : ordered.get(0).id;
        }
        Collections.rotate(ordered, -start);
        return new Attempt(this, ordered);
    }
    private synchronized void limited(String failedId, String nextId) {
        if (preferredId == null || preferredId.equals(failedId)) preferredId = nextId;
    }
    static final class Attempt {
        private final KeyRotation owner;
        private final List<Entry> keys;
        private int index;
        Attempt(KeyRotation owner, List<Entry> keys) { this.owner = owner; this.keys = keys; }
        boolean isEmpty() { return keys.isEmpty(); }
        String key() { return keys.get(index).secret; }
        boolean advance() {
            if (isEmpty()) return false;
            String failed = keys.get(index).id;
            // Even if this attempt is exhausted, prefer the next key on a later request.
            String next = keys.get((index + 1) % keys.size()).id;
            owner.limited(failed, next);
            if (index + 1 >= keys.size()) return false;
            index++;
            return true;
        }
    }
    <T> T run(List<Entry> keys, BooleanSupplier active, Operation<T> operation) throws Exception {
        Attempt attempt = attempt(keys);
        if (attempt.isEmpty()) throw new IOException("Добавьте API-ключ Gemini в веб-настройках");
        while (active.getAsBoolean()) {
            try { return operation.run(attempt.key()); }
            catch (RateLimit e) {
                boolean next = attempt.advance();
                if (!next || !e.retrySafe || !active.getAsBoolean()) throw e;
                onSwitch.run();
            }
        }
        throw new java.io.InterruptedIOException("Запрос отменён");
    }
}
