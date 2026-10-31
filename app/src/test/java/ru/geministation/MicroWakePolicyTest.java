package ru.geministation;

import org.junit.Test;
import static org.junit.Assert.*;

public class MicroWakePolicyTest {
    @Test public void defaultAndValidation() {
        assertEquals(MicroWakePolicy.POCKET, MicroWakePolicy.normalizeEngine(null));
        assertEquals(MicroWakePolicy.POCKET, MicroWakePolicy.normalizeEngine("other"));
        assertTrue(MicroWakePolicy.validEngine(MicroWakePolicy.POCKET));
        assertTrue(MicroWakePolicy.validEngine(MicroWakePolicy.MICRO));
        assertFalse(MicroWakePolicy.validEngine(""));
    }
    @Test public void sensitivityGoesFromStrictToSensitive() {
        assertEquals(.99f, MicroWakePolicy.threshold("1"), .0001);
        assertEquals(.90f, MicroWakePolicy.threshold("50"), .0001);
        assertEquals(.50f, MicroWakePolicy.threshold("100"), .0001);
        float last = 1f;
        for (int i = 1; i <= 100; i++) {
            float value = MicroWakePolicy.threshold(Integer.toString(i));
            assertTrue(value < last); last = value;
        }
    }
}
