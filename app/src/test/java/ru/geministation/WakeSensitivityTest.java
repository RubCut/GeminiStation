package ru.geministation;

import org.junit.Test;
import java.util.HashSet;
import java.util.Set;
import static org.junit.Assert.*;

public class WakeSensitivityTest {
    @Test public void percentagesAreStrictlyOrderedAndHaveDistinctValidThresholds() {
        double previous = Double.POSITIVE_INFINITY;
        Set<String> thresholds = new HashSet<>();
        for (int percent = 1; percent <= 100; percent++) {
            String value = String.valueOf(percent);
            assertTrue(WakeSensitivity.valid(value));
            assertEquals(value, WakeSensitivity.normalize(value));
            String threshold = WakeSensitivity.threshold(value);
            double numeric = Double.parseDouble(threshold);
            assertTrue("threshold must decrease with sensitivity: " + percent, numeric > 0 && numeric < previous);
            assertTrue("threshold must be unique: " + percent, thresholds.add(threshold));
            previous = numeric;
            for (String word : WakeWordResult.KEYWORDS)
                assertTrue(WakeSensitivity.keywords(value).contains(word + " /" + threshold + "/\n"));
        }
    }
    @Test public void legacySettingsKeepExactlyTheSameThresholds() {
        assertEquals("1", WakeSensitivity.normalize("low"));
        assertEquals("50", WakeSensitivity.normalize("normal"));
        assertEquals("100", WakeSensitivity.normalize("high"));
        assertEquals("1e-15", WakeSensitivity.threshold("low"));
        assertEquals("1e-25", WakeSensitivity.threshold("normal"));
        assertEquals("1e-35", WakeSensitivity.threshold("high"));
        assertEquals(WakeSensitivity.threshold("high"), WakeSensitivity.threshold("100"));
    }
    @Test public void rejectsOutOfRangeOrMalformedValues() {
        for (String value : new String[]{null, "", "0", "101", "-1", "01", "1.5", "1e-1000", " 50"})
            assertFalse("unexpected valid value: " + value, WakeSensitivity.valid(value));
        assertEquals("50", WakeSensitivity.normalize(null));
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
