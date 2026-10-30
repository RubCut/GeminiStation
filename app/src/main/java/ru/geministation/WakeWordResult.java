package ru.geministation;

import java.util.Locale;

final class WakeWordResult {
    // PocketSphinx keyword search uses one pronunciation per word; each stress variant has its own alias.
    static final String[] KEYWORDS = {"gemini1", "gemini2", "gemini3", "jeminai1", "jeminai2", "jeminai3", "jemini1"};
    static boolean matches(String hypothesis) {
        if (hypothesis == null) return false;
        for (String word : hypothesis.toLowerCase(Locale.ROOT).trim().split("\\s+")) {
            for (String keyword : KEYWORDS) if (word.equals(keyword)) return true;
        }
        return false;
    }
    private WakeWordResult() { }
}
