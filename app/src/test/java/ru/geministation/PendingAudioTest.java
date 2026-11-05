package ru.geministation;

import org.junit.Test;
import static org.junit.Assert.*;

public class PendingAudioTest {
    @Test public void fifoBoundAndClear() {
        PendingAudio buffer = new PendingAudio(6);
        byte[] a = {1, 2}, b = {3, 4, 5, 6};
        assertTrue(buffer.offer(a)); assertTrue(buffer.offer(b));
        assertEquals(6, buffer.sizeBytes());
        assertFalse(buffer.offer(new byte[]{7}));
        assertSame(a, buffer.poll()); assertEquals(4, buffer.sizeBytes());
        assertTrue(buffer.offer(new byte[]{7}));
        assertSame(b, buffer.poll());
        assertArrayEquals(new byte[]{7}, buffer.poll());
        assertTrue(buffer.isEmpty()); assertNull(buffer.poll());
        assertTrue(buffer.offer(a)); buffer.clear(); assertEquals(0, buffer.sizeBytes());
    }
}
