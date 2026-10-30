package ru.geministation;

import org.junit.Test;
import static org.junit.Assert.*;

public class DnsSettingsTest {
    @Test public void policyFollowsUserPreferenceAndVpn() {
        assertEquals(DnsSettings.Mode.CUSTOM, DnsSettings.mode(true, true, false));
        assertEquals(DnsSettings.Mode.SYSTEM, DnsSettings.mode(true, true, true));
        assertEquals(DnsSettings.Mode.CUSTOM, DnsSettings.mode(true, false, true));
        assertEquals(DnsSettings.Mode.SYSTEM, DnsSettings.mode(false, false, false));
    }
    @Test public void onlyNumericDnsOrHttpsDohAreAccepted() {
        for (String server : new String[]{DnsSettings.DEFAULT_SERVER, "1.1.1.1", "8.8.8.8", "2606:4700:4700::1111", "https://1.1.1.1/dns-query"})
            assertTrue(server, DnsSettings.validServer(server));
        for (String server : new String[]{"", "dns.example.com", "999.1.1.1", "01.1.1.1", "http://1.1.1.1/dns-query", "https://user:secret@dns.example/dns-query", "https://dns.example/dns-query?secret=1", "https://dns.example/#fragment", "https://dns.example:0/dns-query"})
            assertFalse(server, DnsSettings.validServer(server));
    }
}
