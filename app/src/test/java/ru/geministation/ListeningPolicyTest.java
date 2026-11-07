package ru.geministation;

import org.junit.Test;
import static org.junit.Assert.*;

public class ListeningPolicyTest {
    @Test public void fiveSecondsOfSilenceAndOnlyOneShortSignalGrace() {
        ListeningPolicy policy = new ListeningPolicy();
        assertEquals(5000, ListeningPolicy.NO_SPEECH_MS);
        assertEquals(1000, ListeningPolicy.SIGNAL_GRACE_MS);
        assertFalse(policy.grantGrace(false));
        assertTrue(policy.grantGrace(true));
        assertFalse(policy.grantGrace(true));
        policy.reset();
        assertTrue(policy.grantGrace(true));
    }
    @Test public void bothCaptureAndConnectionAreRequiredBeforeSilenceTimer() {
        assertFalse(ListeningPolicy.startSilenceTimer(true, false, false, false));
        assertFalse(ListeningPolicy.startSilenceTimer(false, true, false, false));
        assertTrue(ListeningPolicy.startSilenceTimer(true, true, false, false));
        assertFalse(ListeningPolicy.startSilenceTimer(true, true, true, false));
        assertFalse(ListeningPolicy.startSilenceTimer(true, true, false, true));
    }
}
