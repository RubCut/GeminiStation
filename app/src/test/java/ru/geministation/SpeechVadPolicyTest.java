package ru.geministation;

import org.junit.Test;
import static org.junit.Assert.*;

public class SpeechVadPolicyTest {
    @Test public void needsSpeechAndDoesNotRequireExactWakeScoreAlignment() {
        SpeechVadPolicy vad = new SpeechVadPolicy();
        assertFalse(vad.speechRecently());
        vad.add(.19f); assertFalse(vad.speechRecently());
        vad.add(.20f); assertTrue(vad.speechRecently());
        for (int i = 0; i < SpeechVadPolicy.WINDOW - 1; i++) vad.add(0f);
        assertTrue(vad.speechRecently());
        vad.add(0f); assertFalse(vad.speechRecently());
    }
    @Test public void diagnosticResetDoesNotChangeSpeechHistory() {
        SpeechVadPolicy vad = new SpeechVadPolicy();
        vad.add(.8f); vad.add(.1f);
        assertEquals(.8f, vad.takeIntervalPeak(), 0);
        assertEquals(0, vad.takeIntervalPeak(), 0);
        assertTrue(vad.speechRecently());
    }
}
