package ru.geministation;

import java.net.URI;

/** Only ordinary web links can be offered to installed visible apps, never intent/file/javascript URIs. */
final class LinkPolicy {
    static boolean allowed(String value) {
        if (value == null || value.length() > 2048 || value.isEmpty()) return false;
        try {
            URI uri = new URI(value);
            String scheme = uri.getScheme();
            return ("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme))
                    && uri.getHost() != null && !uri.getHost().isEmpty() && uri.getRawUserInfo() == null
                    && uri.getPort() >= -1 && uri.getPort() <= 65535;
        } catch (Exception e) { return false; }
    }
    static boolean searchQuery(String value) {
        return value != null && !value.trim().isEmpty() && value.length() <= 160
                && !value.chars().anyMatch(Character::isISOControl);
    }
    private LinkPolicy() { }
}
