package ru.geministation;

import org.junit.Test;
import static org.junit.Assert.*;

public class CueGateTest {
    @Test public void completionRunsExactlyOnce() {
        CueGate gate = new CueGate();
        int[] calls = {0};
        int token = gate.begin(() -> calls[0]++);
        gate.finish(token);
        gate.finish(token);
        assertEquals(1, calls[0]);
    }

    @Test public void cancelledAndReplacedCuesCannotResumeOldDialogs() {
        CueGate gate = new CueGate();
        int[] calls = {0, 0, 0};
        int old = gate.begin(() -> calls[0]++);
        int current = gate.begin(() -> calls[1]++);
        gate.finish(old);
        gate.finish(current);
        int cancelled = gate.begin(() -> calls[2]++);
        gate.cancel();
        gate.finish(cancelled);
        assertArrayEquals(new int[]{0, 1, 0}, calls);
    }

    @Test public void continuationCanStartAnotherCue() {
        CueGate gate = new CueGate();
        int[] calls = {0};
        int[] next = {0};
        int first = gate.begin(() -> next[0] = gate.begin(() -> calls[0]++));
        gate.finish(first);
        gate.finish(first);
        gate.finish(next[0]);
        assertEquals(1, calls[0]);
    }
}
