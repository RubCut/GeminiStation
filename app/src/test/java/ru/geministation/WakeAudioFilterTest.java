package ru.geministation;

import org.junit.Test;
import static org.junit.Assert.*;

public class WakeAudioFilterTest {
    private static short[] sine(int amplitude) {
        short[] frame = new short[1600];
        for (int i = 0; i < frame.length; i++) frame[i] = (short) (amplitude * Math.sin(2 * Math.PI * 200 * i / 16000));
        return frame;
    }
    @Test public void silenceAndSingleTransientDoNotPassDurationGate() {
        WakeAudioFilter filter = new WakeAudioFilter();
        for (int i = 0; i < 15; i++) filter.process(new short[1600], 1600, true);
        short[] impulse = new short[1600]; impulse[400] = 20000;
        filter.process(impulse, impulse.length, true);
        assertFalse(filter.speechLike());
    }
    @Test public void sustainedQuietSignalCanPassAndGetsBoundedBoost() {
        WakeAudioFilter filter = new WakeAudioFilter();
        for (int i = 0; i < 15; i++) filter.process(new short[1600], 1600, true);
        short[] frame = null;
        for (int i = 0; i < 4; i++) { frame = sine(300); filter.process(frame, frame.length, true); }
        assertTrue(filter.speechLike());
        assertTrue(filter.gainPercent() > 100 && filter.gainPercent() <= 350);
        int peak = 0; for (short sample : frame) peak = Math.max(peak, Math.abs((int) sample));
        assertTrue(peak > 300 && peak < 1100);
        filter.clearSpeech(); assertFalse(filter.speechLike());
    }
    @Test public void disabledBoostDoesNotMutateInputAndDcIsNotSpeech() {
        WakeAudioFilter filter = new WakeAudioFilter();
        short[] original = sine(300), copy = original.clone();
        filter.process(copy, copy.length, false);
        assertArrayEquals(original, copy);
        filter = new WakeAudioFilter();
        short[] dc = new short[1600]; java.util.Arrays.fill(dc, (short) 1000);
        for (int i = 0; i < 10; i++) filter.process(dc, dc.length, false);
        assertFalse(filter.speechLike());
    }
    @Test public void gainNeverOverflowsPcm() {
        WakeAudioFilter filter = new WakeAudioFilter();
        for (int i = 0; i < 10; i++) filter.process(new short[1600], 1600, true);
        for (int i = 0; i < 6; i++) filter.process(sine(100), 1600, true);
        short[] loud = sine(30000);
        filter.process(loud, loud.length, true);
        for (short value : loud) assertTrue(value >= Short.MIN_VALUE && value <= Short.MAX_VALUE);
    }
}
