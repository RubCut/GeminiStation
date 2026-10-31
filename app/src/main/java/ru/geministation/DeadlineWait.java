package ru.geministation;

import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** A shared time budget; timeout does not cancel a useful background prefetch. */
final class DeadlineWait {
    static <T> T get(Future<T> future, long deadlineNanos, T fallback) throws InterruptedException {
        if (future == null) return fallback;
        try { return future.get(Math.max(0L, deadlineNanos - System.nanoTime()), TimeUnit.NANOSECONDS); }
        catch (TimeoutException | ExecutionException | CancellationException e) { return fallback; }
    }
    private DeadlineWait() { }
}
