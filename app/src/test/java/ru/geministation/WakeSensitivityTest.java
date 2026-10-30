package ru.geministation;

import org.junit.Test;
import static org.junit.Assert.*;

public class WakeSensitivityTest {
    @Test public void levelsAreFiniteAndGenerateSeparateKeywordThresholds() {
        assertTrue(WakeSensitivity.valid("low"));
        assertTrue(WakeSensitivity.valid("normal"));
        assertTrue(WakeSensitivity.valid("high"));
        assertFalse(WakeSensitivity.valid(null));
        assertFalse(WakeSensitivity.valid("1e-1000"));
        assertEquals("1e-15", WakeSensitivity.threshold("low"));
        assertEquals("1e-25", WakeSensitivity.threshold("normal"));
        assertEquals("1e-35", WakeSensitivity.threshold("high"));
        assertEquals("gemini /1e-35/\njeminai /1e-35/\njemini /1e-35/\n", WakeSensitivity.keywords("high"));
    }
    @Test public void diagnosticsAggregateAndResetWithoutRecording() {
        AudioLevel level = new AudioLevel();
        level.add(new short[] {0, 1000, -1000, Short.MIN_VALUE}, 3);
        assertEquals(816, level.rms());
        assertEquals(1000, level.peak());
        level.add(new short[] {Short.MIN_VALUE}, 1);
        assertEquals(32768, level.peak());
        level.reset();
        assertEquals(0, level.rms());
        assertEquals(0, level.peak());
    }
}
