package ru.geministation;

import java.net.Inet4Address;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.Locale;

/** No password by request. Block cross-origin browser requests and DNS rebinding. */
final class WebSecurity {
    static boolean permittedHost(String host) {
        if (host == null) return false;
        String normalized = host.toLowerCase(Locale.ROOT);
        if (normalized.equals("localhost:6529") || normalized.equals("127.0.0.1:6529")) return true;
        try {
            for (NetworkInterface iface : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!iface.isUp()) continue;
                for (java.net.InetAddress ip : Collections.list(iface.getInetAddresses())) {
                    if (ip instanceof Inet4Address && normalized.equals(ip.getHostAddress() + ":6529")) return true;
                }
            }
        } catch (Exception ignored) { }
        return false;
    }
    static boolean localLedWrite(String remote, String host, String origin, String marker) {
        return "127.0.0.1".equals(remote) && ("127.0.0.1:6529".equals(host) || "localhost:6529".equals(host))
                && permittedWrite(host, origin, marker);
    }
    static boolean permittedWrite(String host, String origin, String marker) {
        return permittedHost(host) && origin != null && origin.equalsIgnoreCase("http://" + host)
                && "1".equals(marker);
    }
    private WebSecurity() { }
}
