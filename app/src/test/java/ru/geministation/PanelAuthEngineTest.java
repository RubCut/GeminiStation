package ru.geministation;

import org.junit.Test;
import static org.junit.Assert.*;

public class PanelAuthEngineTest {
    static class Store implements PanelAuthEngine.Store {
        boolean enabled; String salt = "", hash = ""; boolean fail;
        public boolean enabled() { return enabled; }
        public String salt() { return salt; }
        public String hash() { return hash; }
        public boolean save(String s, String h) { if (fail) return false; enabled = true; salt = s; hash = h; return true; }
        public boolean clear() { if (fail) return false; enabled = false; salt = hash = ""; return true; }
    }
    @Test public void disabledAndPasswordSessionsAndRevoke() throws Exception {
        Store s = new Store(); long[] now = {1}; PanelAuthEngine a = new PanelAuthEngine(s, () -> now[0]);
        assertTrue(a.authorized(null));
        assertFalse(PanelAuthEngine.validPassword("short"));
        String first = a.setPassword("a test password");
        assertTrue(s.enabled); assertFalse(s.hash.contains("a test password"));
        assertFalse(a.authorized(null)); assertFalse(a.authorized("gemini_session=bad"));
        assertTrue(a.authorized(PanelAuthEngine.cookie(first)));
        assertNull(a.login("10.0.0.1", "incorrect"));
        String second = a.login("10.0.0.1", "a test password"); assertNotNull(second);
        assertTrue(a.authorized("other=x; gemini_session=" + second));
        String third = a.setPassword("another password");
        assertFalse(a.authorized(PanelAuthEngine.cookie(first))); assertFalse(a.authorized(PanelAuthEngine.cookie(second)));
        assertTrue(a.authorized(PanelAuthEngine.cookie(third)));
        now[0] += 12 * 60 * 60 * 1000L; assertFalse(a.authorized(PanelAuthEngine.cookie(third)));
        assertTrue(a.disable()); assertTrue(a.authorized(null));
    }
    @Test public void rateLimitAndFailureAreSafe() throws Exception {
        Store s = new Store(); long[] now = {0}; PanelAuthEngine a = new PanelAuthEngine(s, () -> now[0]);
        String token = a.setPassword("password one");
        for (int i = 0; i < 5; i++) assertNull(a.login("peer", "password wrong"));
        assertNull(a.login("peer", "password one"));
        assertNotNull(a.login("other", "password one"));
        now[0] = 60000; assertNotNull(a.login("peer", "password one"));
        s.fail = true; assertFalse(a.disable()); assertTrue(a.authorized(PanelAuthEngine.cookie(token)));
        try { a.setPassword("password two"); fail(); } catch (IllegalStateException expected) { }
        assertTrue(a.authorized(PanelAuthEngine.cookie(token)));
    }
}
