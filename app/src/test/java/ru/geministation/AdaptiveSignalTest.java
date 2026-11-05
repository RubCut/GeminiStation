package ru.geministation;

import org.junit.Test;
import static org.junit.Assert.*;

public class AdaptiveSignalTest {
    @Test public void detectsQuietSignalAboveLowBackground() {
        AdaptiveSignal signal = new AdaptiveSignal();
        for (int i=0; i<40; i++) assertFalse(signal.accept(20));
        assertTrue(signal.accept(150));
    }
    @Test public void learnsStationaryBackgroundWithoutImmediatelyAbsorbingSpeech() {
        AdaptiveSignal signal = new AdaptiveSignal();
        for (int i=0; i<40; i++) signal.accept(20);
        for (int i=0; i<40; i++) assertTrue(signal.accept(300));
        for (int i=0; i<1200; i++) signal.accept(300);
        assertFalse(signal.accept(300));
        assertTrue(signal.accept(900));
    }
}
