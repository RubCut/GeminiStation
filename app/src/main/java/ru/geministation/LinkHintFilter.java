package ru.geministation;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/** Manifest hints only; actual link opening always uses Android's resolver for the exact URL. */
final class LinkHintFilter {
    boolean view, browsable, defaultCategory;
    private final Set<String> schemes = new LinkedHashSet<>(), hosts = new LinkedHashSet<>();
    void data(String scheme, String host) {
        if (scheme != null) {
            String lower = scheme.toLowerCase(Locale.ROOT);
            if ("http".equals(lower) || "https".equals(lower)) schemes.add(lower);
        }
        if (host != null && host.length() <= 253 && host.matches("(?:\\*\\.)?[A-Za-z0-9.-]+")) hosts.add(host.toLowerCase(Locale.ROOT));
    }
    Set<String> hints() {
        Set<String> hints = new LinkedHashSet<>();
        if (!view || !browsable || !defaultCategory) return hints;
        for (String scheme : schemes) {
            if (hosts.isEmpty()) hints.add(scheme + "://*");
            else for (String host : hosts) hints.add(scheme + "://" + host);
        }
        return hints;
    }
}
