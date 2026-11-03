package ru.geministation;

import org.junit.Test;
import static org.junit.Assert.*;

public class MicroWakePolicyTest {
    @Test public void migrationAndValidation() {
        assertEquals(MicroWakePolicy.SELECTED_TTS, MicroWakePolicy.normalizeEngine(null));
        assertEquals(MicroWakePolicy.SELECTED_TTS, MicroWakePolicy.normalizeEngine("other"));
        assertEquals(MicroWakePolicy.SELECTED_TTS, MicroWakePolicy.normalizeEngine("pocketsphinx"));
        assertEquals(MicroWakePolicy.SELECTED_TTS, MicroWakePolicy.normalizeEngine("microwakeword_all_stress"));
        assertEquals(MicroWakePolicy.MICRO, MicroWakePolicy.normalizeEngine(MicroWakePolicy.MICRO));
        assertTrue(MicroWakePolicy.validEngine(MicroWakePolicy.MICRO));
        assertTrue(MicroWakePolicy.validEngine(MicroWakePolicy.SELECTED_TTS));
        assertFalse(MicroWakePolicy.validEngine("pocketsphinx"));
        assertFalse(MicroWakePolicy.validEngine("microwakeword_all_stress"));
        assertEquals("microwakeword/gemini_experimental.tflite", MicroWakePolicy.modelAsset(MicroWakePolicy.MICRO));
        assertEquals("microwakeword/gemini_selected_tts_experimental.tflite", MicroWakePolicy.modelAsset(MicroWakePolicy.SELECTED_TTS));
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
