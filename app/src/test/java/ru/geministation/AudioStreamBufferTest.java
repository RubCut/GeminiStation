package ru.geministation;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class AudioStreamBufferTest {
    private static final class Sink implements AudioStreamBuffer.Sink {
        final List<String> events = new ArrayList<>();
        public void audio(byte[] pcm) { events.add("pcm" + pcm[0]); }
        public void end() { events.add("end"); }
    }
    @Test public void audioIsBufferedUntilReadyAndKeptInOrder() throws Exception {
        AudioStreamBuffer b = new AudioStreamBuffer(10); Sink sink = new Sink();
        assertTrue(b.write(new byte[]{1}, sink)); assertTrue(b.write(new byte[]{2}, sink));
        assertTrue(sink.events.isEmpty());
        assertEquals(2, b.connect(sink));
        assertTrue(b.write(new byte[]{3}, sink)); b.finish(sink); b.finish(sink);
        assertEquals(Arrays.asList("pcm1", "pcm2", "pcm3", "end"), sink.events);
        assertFalse(b.write(new byte[]{4}, sink));
    }
    @Test public void finishDuringConnectionDefersEndUntilAfterBufferedAudio() throws Exception {
        AudioStreamBuffer b = new AudioStreamBuffer(10); Sink sink = new Sink();
        b.write(new byte[]{1}, sink); b.finish(sink);
        assertTrue(sink.events.isEmpty());
        b.connect(sink); b.connect(sink); b.finish(sink);
        assertEquals(Arrays.asList("pcm1", "end"), sink.events);
    }
    @Test public void limitAndCancelNeverLeakQueuedAudio() throws Exception {
        AudioStreamBuffer b = new AudioStreamBuffer(1); Sink sink = new Sink();
        assertTrue(b.write(new byte[]{1}, sink)); assertFalse(b.write(new byte[]{2}, sink));
        b.cancel(); b.connect(sink); b.finish(sink);
        assertTrue(sink.events.isEmpty()); assertFalse(b.write(new byte[]{3}, sink));
    }
}
