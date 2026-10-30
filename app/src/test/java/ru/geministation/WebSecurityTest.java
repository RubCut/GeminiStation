package ru.geministation;

import org.junit.Test;
import static org.junit.Assert.*;

public class WebSecurityTest {
    @Test public void localHostWorks() {
        assertTrue(WebSecurity.permittedHost("127.0.0.1:6529"));
        assertTrue(WebSecurity.permittedWrite("localhost:6529", "http://localhost:6529", "1"));
    }
    @Test public void ledSecretsRequireLoopbackAndSameOrigin() {
        assertTrue(WebSecurity.localLedWrite("127.0.0.1", "127.0.0.1:6529", "http://127.0.0.1:6529", "1"));
        assertFalse(WebSecurity.localLedWrite("192.168.1.42", "127.0.0.1:6529", "http://127.0.0.1:6529", "1"));
        assertFalse(WebSecurity.localLedWrite("127.0.0.1", "127.0.0.1:6529", "http://evil.example", "1"));
        assertFalse(WebSecurity.localLedWrite("127.0.0.1", "127.0.0.1:6529", "http://127.0.0.1:6529", null));
    }
    @Test public void rejectsDnsRebindingAndCrossOrigin() {
        assertFalse(WebSecurity.permittedHost("evil.example:6529"));
        assertFalse(WebSecurity.permittedWrite("localhost:6529", "http://evil.example:6529", "1"));
        assertFalse(WebSecurity.permittedWrite("localhost:6529", null, "1"));
        assertFalse(WebSecurity.permittedWrite("localhost:6529", "http://localhost:6529", null));
    }
}
