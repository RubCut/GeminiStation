package ru.geministation;

import org.junit.Test;
import static org.junit.Assert.*;

public class AudioFocusSessionTest {
    private static final class Backend implements AudioFocusSession.Backend {
        int requests, abandons;
        boolean granted = true;
        @Override public boolean request() { requests++; return granted; }
        @Override public void abandon() { abandons++; }
    }
    @Test public void activateAnswerReleaseAndFollowUpReacquire() {
        Backend backend = new Backend();
        AudioFocusSession focus = new AudioFocusSession(backend);
        assertTrue(focus.acquire()); // activation
        assertTrue(focus.acquire()); // recognized speech
        assertTrue(focus.acquire()); // start of TTS
        assertEquals(1, backend.requests);
        focus.release(); // answer complete
        focus.release(); // subsequent cleanup is idempotent
        assertEquals(1, backend.abandons);
        assertTrue(focus.acquire()); // first speech of follow-up
        assertEquals(2, backend.requests);
        focus.release(); // stopped/error
        assertEquals(2, backend.abandons);
    }
    @Test public void deniedFocusDoesNotRequireAbandonAndCanRetry() {
        Backend backend = new Backend(); backend.granted = false;
        AudioFocusSession focus = new AudioFocusSession(backend);
        assertFalse(focus.acquire());
        focus.release();
        assertEquals(0, backend.abandons);
        backend.granted = true;
        assertTrue(focus.acquire());
        focus.release();
        assertEquals(2, backend.requests);
        assertEquals(1, backend.abandons);
    }
    @Test public void permanentLossAllowsRequestAgainWithoutAbandoningRemovedFocus() {
        Backend backend = new Backend();
        AudioFocusSession focus = new AudioFocusSession(backend);
        assertTrue(focus.acquire());
        focus.permanentlyLost();
        focus.release();
        assertEquals(0, backend.abandons);
        assertTrue(focus.acquire());
        focus.release();
        assertEquals(2, backend.requests);
        assertEquals(1, backend.abandons);
    }
}
