package ru.geministation;

import org.junit.Test;
import static org.junit.Assert.*;

public class TranscriptAccumulatorTest {
    @Test public void interimHypothesesReplaceRatherThanAccumulate() {
        TranscriptAccumulator state = new TranscriptAccumulator();
        assertEquals("Прив", state.partial("Прив"));
        assertEquals("Привет", state.partial("Привет"));
        assertEquals("Привет", state.addFinal("Привет"));
        assertEquals("Привет мир", state.partial("мир"));
        assertEquals("Привет мир", state.best());
    }
    @Test public void mergesCumulativeAndDeltaFinalsWithoutDuplicates() {
        TranscriptAccumulator state = new TranscriptAccumulator();
        assertEquals("Привет", state.addFinal("Привет"));
        assertEquals("Привет мир", state.addFinal("Привет мир"));
        assertEquals("Привет мир", state.addFinal("мир"));
        assertEquals("Привет мир!", state.addFinal("!"));
        assertTrue(state.hasFinal());
    }
    @Test public void partialCanStandAloneAsFallback() {
        TranscriptAccumulator state = new TranscriptAccumulator();
        state.partial("Как дела");
        assertEquals("Как дела", state.best());
        assertFalse(state.hasFinal());
    }
}
