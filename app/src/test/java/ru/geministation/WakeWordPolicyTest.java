package ru.geministation;

import org.junit.Test;
import static org.junit.Assert.*;

public class WakeWordPolicyTest {
    @Test public void onlyEnabledServiceWithPermissionOutsideDialogListens() {
        assertTrue(WakeWordPolicy.shouldListen(true, true, false, true));
        assertFalse(WakeWordPolicy.shouldListen(false, true, false, true));
        assertFalse(WakeWordPolicy.shouldListen(true, false, false, true));
        assertFalse(WakeWordPolicy.shouldListen(true, true, true, true));
        assertFalse(WakeWordPolicy.shouldListen(true, true, false, false));
    }
    @Test public void onlyKnownKeywordResultsTrigger() {
        assertTrue(WakeWordResult.matches("gemini"));
        assertTrue(WakeWordResult.matches("jeminai"));
        assertTrue(WakeWordResult.matches("jemini"));
        assertTrue(WakeWordResult.matches("gemini jeminai"));
        assertFalse(WakeWordResult.matches(null));
        assertFalse(WakeWordResult.matches("notgemini"));
        assertFalse(WakeWordResult.matches(""));
    }
}
