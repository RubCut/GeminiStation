package ru.geministation;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Personal matching never blocks microphone capture. No background audio is persisted. */
final class PersonalWakeWorker implements AutoCloseable {
    private final PersonalWakeMatcher matcher;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicBoolean pending = new AtomicBoolean(), active = new AtomicBoolean(true);
    private final AtomicLong hitAt = new AtomicLong();
    private long nextOffer;
    PersonalWakeWorker(List<float[][]> templates) { matcher = new PersonalWakeMatcher(templates); }
    void add(float[] features) { matcher.add(features); }
    void offer(long now) {
        if (!active.get() || now < nextOffer || !pending.compareAndSet(false, true)) return;
        nextOffer = now + 300;
        float[][] snapshot = matcher.snapshot();
        if (snapshot.length < 10) { pending.set(false); return; }
        try {
            worker.execute(() -> {
                try {
                    float score = matcher.best(snapshot);
                    if (active.get() && score < PersonalWakeMatcher.CUTOFF) hitAt.set(now);
                } finally { pending.set(false); }
            });
        } catch (java.util.concurrent.RejectedExecutionException e) { pending.set(false); }
    }
    boolean takeHit(long now) {
        long value = hitAt.getAndSet(0);
        return value > 0 && now >= value && now - value <= 500;
    }
    @Override public void close() { active.set(false); hitAt.set(0); worker.shutdownNow(); }
}
