package ru.geministation;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import static org.junit.Assert.*;

public class DeadlineWaitTest {
    @Test public void handlesCompletedFailedAndMissingTasks() throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        assertEquals("ok", DeadlineWait.get(CompletableFuture.completedFuture("ok"), end, "fallback"));
        CompletableFuture<String> failed = new CompletableFuture<>(); failed.completeExceptionally(new Exception());
        assertEquals("fallback", DeadlineWait.get(failed, end, "fallback"));
        assertEquals("fallback", DeadlineWait.get(null, end, "fallback"));
    }
    @Test public void sharesDeadlineAndDoesNotCancelPendingWork() throws Exception {
        CompletableFuture<String> first = new CompletableFuture<>(), second = new CompletableFuture<>();
        long start = System.nanoTime(), deadline = start + TimeUnit.MILLISECONDS.toNanos(40);
        assertEquals("fallback", DeadlineWait.get(first, deadline, "fallback"));
        assertEquals("fallback", DeadlineWait.get(second, deadline, "fallback"));
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 400);
        assertFalse(first.isCancelled()); first.complete("late");
        assertEquals("late", first.get());
    }
}
