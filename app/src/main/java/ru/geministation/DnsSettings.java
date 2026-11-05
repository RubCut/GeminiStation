package ru.geministation;

import java.net.URI;
import java.net.InetAddress;
import java.net.Inet6Address;

/** Validation and effective DNS policy; no Android dependencies. */
final class DnsSettings {
    static final String DEFAULT_SERVER = "https://d.webshiftvpn.ru/dns-query";
    enum Mode { SYSTEM, CUSTOM }
    static Mode mode(boolean enabled, boolean bypassOnVpn, boolean vpnActive) {
        return enabled && !(bypassOnVpn && vpnActive) ? Mode.CUSTOM : Mode.SYSTEM;
    }
    static boolean validServer(String value) { return isIp(value) || isDoh(value); }
    static boolean isIp(String value) {
        if (value == null || value.length() > 45) return false;
        if (value.indexOf(':') >= 0) {
            if (!value.matches("[0-9a-fA-F:.]+")) return false;
            try { return InetAddress.getByName(value) instanceof Inet6Address; } catch (Exception e) { return false; }
        }
        if (!value.matches("(?:0|[1-9][0-9]{0,2})(?:\\.(?:0|[1-9][0-9]{0,2})){3}")) return false;
        for (String part : value.split("\\.")) if (Integer.parseInt(part) > 255) return false;
        return true;
    }
    static boolean isDoh(String value) {
        if (value == null || value.length() > 2048 || !value.equals(value.trim())) return false;
        try {
            URI uri = new URI(value);
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null
                    && uri.getUserInfo() == null && uri.getRawQuery() == null && uri.getFragment() == null
                    && uri.getPort() <= 65535 && uri.getPort() != 0;
        } catch (Exception e) { return false; }
    }
    private DnsSettings() { }
}
