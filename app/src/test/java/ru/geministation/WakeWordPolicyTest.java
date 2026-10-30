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
        for (String word : WakeWordResult.KEYWORDS) assertTrue(WakeWordResult.matches(word));
        assertTrue(WakeWordResult.matches("gemini1 jeminai3"));
        assertFalse(WakeWordResult.matches(null));
        assertFalse(WakeWordResult.matches("notgemini1"));
        assertFalse(WakeWordResult.matches("gemini"));
        assertFalse(WakeWordResult.matches(""));
    }
}
