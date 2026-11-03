package ru.geministation;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.Test;
import static org.junit.Assert.*;

public class PersonalWakeAudioTest {
    private byte[] wav(int rate, int channels, int durationMs, int fromMs, int untilMs, int amplitude) {
        int frames = rate * durationMs / 1000;
        ByteBuffer out = ByteBuffer.allocate(44 + frames * channels * 2).order(ByteOrder.LITTLE_ENDIAN);
        out.put(new byte[]{'R','I','F','F'}).putInt(36 + frames * channels * 2).put(new byte[]{'W','A','V','E'});
        out.put(new byte[]{'f','m','t',' '}).putInt(16).putShort((short) 1).putShort((short) channels);
        out.putInt(rate).putInt(rate * channels * 2).putShort((short)(channels * 2)).putShort((short) 16);
        out.put(new byte[]{'d','a','t','a'}).putInt(frames * channels * 2);
        for (int i = 0; i < frames; i++) {
            int millis = i * 1000 / rate;
            short value = (short) (millis >= fromMs && millis < untilMs ? amplitude * Math.sin(2 * Math.PI * 220 * i / rate) : 0);
            for (int c = 0; c < channels; c++) out.putShort(value);
        }
        return out.array();
    }
    @Test public void trimsAndResamplesStereoTo16k() throws Exception {
        PersonalWakeAudio.Clip clip = PersonalWakeAudio.decode(wav(48000, 2, 1000, 300, 750, 4000));
        assertEquals(16000, clip.pcm.length);
        assertTrue(clip.first >= 25 && clip.first <= 30);
        assertTrue(clip.last >= 75 && clip.last <= 79);
    }
    @Test public void rejectsSilenceLongUtteranceAndInvalidData() throws Exception {
        try { PersonalWakeAudio.decode(wav(16000, 1, 1000, 0, 0, 0)); fail(); } catch (java.io.IOException expected) { }
        try { PersonalWakeAudio.decode(wav(16000, 1, 2500, 0, 2000, 4000)); fail(); } catch (java.io.IOException expected) { }
        try { PersonalWakeAudio.decode(new byte[128]); fail(); } catch (java.io.IOException expected) { }
    }
}
