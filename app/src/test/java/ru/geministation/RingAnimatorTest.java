package ru.geministation;
import static org.junit.Assert.*;
import org.junit.Test;

public class RingAnimatorTest {
    @Test public void activationReturnsToFrontAfterFastOrbit() {
        RingAnimator a = new RingAnimator(1000);
        assertEquals(-.5, a.center(1100), .0001);
        assertEquals(11.5, a.center(1447), .03);
        assertEquals(23.5, a.center(1794), .001);
        assertEquals(23.5, a.center(3000), .001);
    }
    @Test public void thinkingCirclesInTwoAndAHalfSeconds() {
        RingAnimator a = new RingAnimator(0); a.setPhase("thinking", 800);
        assertEquals(24.0, a.center(3300)-a.center(800), .0001);
    }
    @Test public void speakingReturnsAndKeepsPulsingAtFront() {
        RingAnimator a = new RingAnimator(0); a.setPhase("thinking", 800);
        a.setPhase("speaking", 1800);
        double front = a.center(2350);
        // Compare on the circle: floating point modulo near 24 can round to 24, not 0.
        assertEquals(1.0, Math.cos((front + .5) * 2 * Math.PI / 24), .0001);
        assertEquals(front, a.center(9000), .0001);
        assertFalse(java.util.Arrays.equals(a.frame(2350, false), a.frame(2875, false)));
    }
    @Test public void blackAndWhiteIsActuallyGrayAndAllFramesHave24Pixels() {
        RingAnimator a = new RingAnimator(0);
        byte[] color = a.frame(500, false), mono = a.frame(500, true);
        assertEquals(72, color.length); assertEquals(72, mono.length);
        boolean lit = false, different = false;
        for (int i = 0; i < 72; i += 3) {
            assertEquals(mono[i], mono[i+1]); assertEquals(mono[i], mono[i+2]);
            lit |= mono[i] != 0; different |= color[i] != color[i+1];
        }
        assertTrue(lit); assertTrue(different);
    }
}
