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
