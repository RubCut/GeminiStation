package ru.geministation;

import org.junit.Test;
import static org.junit.Assert.*;

public class WebSecurityTest {
    @Test public void localHostWorks() {
        assertTrue(WebSecurity.permittedHost("127.0.0.1:6529"));
        assertTrue(WebSecurity.permittedWrite("localhost:6529", "http://localhost:6529", "1"));
    }
    @Test public void rejectsDnsRebindingAndCrossOrigin() {
        assertFalse(WebSecurity.permittedHost("evil.example:6529"));
        assertFalse(WebSecurity.permittedWrite("localhost:6529", "http://evil.example:6529", "1"));
        assertFalse(WebSecurity.permittedWrite("localhost:6529", null, "1"));
        assertFalse(WebSecurity.permittedWrite("localhost:6529", "http://localhost:6529", null));
    }
}
