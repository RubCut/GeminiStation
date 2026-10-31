package ru.geministation.buttonhelper;

import org.junit.Test;
import static org.junit.Assert.*;
import static ru.geministation.buttonhelper.MediaGuardPolicy.Access.*;

public class MediaGuardPolicyTest {
    @Test public void onlyExactDeniedRequestsGrantAndRetriesBackOff() {
        MediaGuardPolicy p = new MediaGuardPolicy();
        assertEquals(DENIED, MediaGuardPolicy.parse("denied"));
        assertEquals(GRANTED, MediaGuardPolicy.parse("granted"));
        for (String value : new String[]{"denied\n", "deniedExtra", "", "unknown", null})
            assertEquals(UNKNOWN, MediaGuardPolicy.parse(value));
        assertFalse(p.shouldGrant(UNKNOWN, 0));
        assertTrue(p.shouldGrant(DENIED, 0));
        assertFalse(p.shouldGrant(DENIED, 2999));
        assertTrue(p.shouldGrant(DENIED, 3000));
        assertFalse(p.shouldGrant(UNKNOWN, 9000));
        assertTrue(p.shouldGrant(DENIED, 9000));
        assertFalse(p.shouldGrant(GRANTED, 9001));
        assertTrue(p.shouldGrant(DENIED, 9002));
    }
    @Test public void repeatedFailuresNeverRetryFasterThanOneMinuteAfterBackoff() {
        MediaGuardPolicy p = new MediaGuardPolicy();
        long time = 1;
        for (long delay : new long[]{3000, 6000, 12000, 24000, 48000, 60000, 60000}) {
            assertTrue(p.shouldGrant(DENIED, time));
            assertFalse(p.shouldGrant(DENIED, time + delay - 1));
            time += delay;
        }
        assertTrue(p.shouldGrant(DENIED, time));
    }
}
