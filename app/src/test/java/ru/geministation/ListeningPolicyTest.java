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
}
