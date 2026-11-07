package ru.geministation;

import org.junit.Test;
import static org.junit.Assert.*;

public class WebSecurityTest {
    @Test public void localHostWorks() {
        assertTrue(WebSecurity.permittedHost("127.0.0.1:6529"));
        assertTrue(WebSecurity.permittedWrite("localhost:6529", "http://localhost:6529", "1"));
    }
    @Test public void sameOriginWritesWorkWithStationNetworkAddress() throws Exception {
        String lan = null;
        for (java.net.NetworkInterface net : java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces())) {
            if (!net.isUp() || net.isLoopback()) continue;
            for (java.net.InetAddress address : java.util.Collections.list(net.getInetAddresses())) {
                if (address instanceof java.net.Inet4Address && !address.isLoopbackAddress()) {
                    lan = address.getHostAddress() + ":6529"; break;
                }
            }
            if (lan != null) break;
        }
        org.junit.Assume.assumeNotNull(lan);
        assertTrue(WebSecurity.permittedWrite(lan, "http://" + lan, "1"));
        assertFalse(WebSecurity.permittedWrite(lan, "http://evil.example", "1"));
        assertFalse(WebSecurity.permittedWrite(lan, "http://" + lan, null));
        assertFalse(WebSecurity.permittedWrite(lan, null, "1"));
    }
    @Test public void loopbackSupportsNumericIpv4AndMappedIpv6Only() {
        assertTrue(WebSecurity.stationLoopback("127.0.0.1"));
        assertTrue(WebSecurity.stationLoopback("::1"));
        assertTrue(WebSecurity.stationLoopback("::ffff:127.0.0.1"));
        assertTrue(WebSecurity.stationLoopback("0:0:0:0:0:ffff:7f00:1"));
        assertFalse(WebSecurity.stationLoopback("192.168.1.5"));
        assertFalse(WebSecurity.stationLoopback("::ffff:192.168.1.5"));
        assertFalse(WebSecurity.stationLoopback("localhost"));
        assertFalse(WebSecurity.stationLoopback("127.0.0.1.evil.example"));
    }
    @Test public void rejectsDnsRebindingAndCrossOrigin() {
        assertFalse(WebSecurity.permittedHost("evil.example:6529"));
        assertFalse(WebSecurity.permittedWrite("localhost:6529", "http://evil.example:6529", "1"));
        assertFalse(WebSecurity.permittedWrite("localhost:6529", null, "1"));
        assertFalse(WebSecurity.permittedWrite("localhost:6529", "http://localhost:6529", null));
    }
}
