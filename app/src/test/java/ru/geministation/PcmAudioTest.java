package ru.geministation;

import org.junit.Test;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import static org.junit.Assert.*;

public class PcmAudioTest {
    private byte[] wav() {
        ByteBuffer b = ByteBuffer.allocate(56).order(ByteOrder.LITTLE_ENDIAN);
        b.put(new byte[]{'R','I','F','F'}).putInt(48).put(new byte[]{'W','A','V','E'});
        b.put(new byte[]{'f','m','t',' '}).putInt(16).putShort((short) 1).putShort((short) 1);
        b.putInt(24000).putInt(48000).putShort((short) 2).putShort((short) 16);
        b.put(new byte[]{'J','U','N','K'}).putInt(3).put(new byte[]{1,2,3,0});
        b.put(new byte[]{'d','a','t','a'}).putInt(0);
        return b.array();
    }
    @Test public void decodesWavWithExtraChunk() throws Exception {
        byte[] raw = wav();
        ByteBuffer b = ByteBuffer.allocate(raw.length + 4).order(ByteOrder.LITTLE_ENDIAN);
        b.put(raw); b.putInt(1234); b.putInt(52, 4);
        PcmAudio pcm = PcmAudio.decode(b.array(), "audio/wav");
        assertEquals(24000, pcm.rate); assertEquals(1, pcm.channels); assertEquals(4, pcm.samples.length);
    }
    @Test public void decodesRawPcm() throws Exception {
        PcmAudio pcm = PcmAudio.decode(new byte[]{1,2,3,4}, "audio/L16;codec=pcm;rate=24000");
        assertEquals(24000, pcm.rate); assertEquals(1, pcm.channels);
    }
    @Test public void browserWavRoundTripsSamplesAndFormat() throws Exception {
        PcmAudio source = PcmAudio.decode(new byte[]{1, 2, 3, 4}, "audio/L16;rate=24000");
        byte[] wav = source.toWav();
        assertEquals(48, wav.length);
        assertEquals(40, ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN).getInt(4));
        PcmAudio decoded = PcmAudio.decode(wav, "audio/wav");
        assertArrayEquals(source.samples, decoded.samples);
        assertEquals(source.rate, decoded.rate); assertEquals(source.channels, decoded.channels);
    }
    @Test public void browserWavRetainsStereoChannels() throws Exception {
        byte[] raw = wav();
        ByteBuffer b = ByteBuffer.allocate(raw.length + 4).order(ByteOrder.LITTLE_ENDIAN);
        b.put(raw).putInt(1234); b.putInt(52, 4); b.putShort(22, (short) 2);
        PcmAudio stereo = PcmAudio.decode(b.array(), "audio/wav");
        PcmAudio decoded = PcmAudio.decode(stereo.toWav(), "audio/wav");
        assertEquals(2, decoded.channels); assertEquals(stereo.rate, decoded.rate);
        assertArrayEquals(stereo.samples, decoded.samples);
    }
    @Test public void activationWarmupPreservesSamplesAndAddsSilentWholeFrames() throws Exception {
        byte[] original = {1, 2, 3, 4};
        PcmAudio source = PcmAudio.decode(original, "audio/L16;rate=24000");
        PcmAudio padded = source.withSilence(350, 150);
        int before = 24000 * 350 / 1000 * 2;
        int after = 24000 * 150 / 1000 * 2;
        assertEquals(24000, padded.rate); assertEquals(1, padded.channels);
        assertEquals(before + original.length + after, padded.samples.length);
        assertArrayEquals(original, java.util.Arrays.copyOfRange(padded.samples, before, before + original.length));
        for (int i = 0; i < before; i++) assertEquals(0, padded.samples[i]);
        for (int i = before + original.length; i < padded.samples.length; i++) assertEquals(0, padded.samples[i]);
        assertArrayEquals(original, source.samples);
    }
    @Test public void warmupRetainsStereoFrameAlignment() throws Exception {
        byte[] raw = wav();
        ByteBuffer b = ByteBuffer.allocate(raw.length + 4).order(ByteOrder.LITTLE_ENDIAN);
        b.put(raw).putInt(1234); b.putInt(52, 4); b.putShort(22, (short) 2);
        PcmAudio source = PcmAudio.decode(b.array(), "audio/wav");
        PcmAudio padded = source.withSilence(1, 1);
        assertEquals(2, padded.channels);
        assertEquals(24 * 4 + 4 + 24 * 4, padded.samples.length);
        assertArrayEquals(source.samples, java.util.Arrays.copyOfRange(padded.samples, 24 * 4, 24 * 4 + 4));
    }
    @Test(expected=IllegalArgumentException.class) public void warmupIsBounded() throws Exception {
        PcmAudio.decode(new byte[]{1,2}, "audio/L16;rate=24000").withSilence(3000, 0);
    }
    @Test(expected=IOException.class) public void rejectsUnknownFormat() throws Exception {
        PcmAudio.decode(new byte[]{1,2}, "audio/mp3");
    }
    @Test(expected=IOException.class) public void rejectsTruncatedWav() throws Exception {
        byte[] raw = wav(); ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).putInt(40, 1000);
        PcmAudio.decode(raw, "audio/wav");
    }
    @Test(expected=IOException.class) public void rejectsOddPcm() throws Exception {
        PcmAudio.decode(new byte[]{1,2,3}, "audio/L16;rate=24000");
    }
}
