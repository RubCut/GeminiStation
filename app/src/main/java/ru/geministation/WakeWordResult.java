package ru.geministation;

import java.util.Locale;

final class WakeWordResult {
    static boolean matches(String hypothesis) {
        if (hypothesis == null) return false;
        for (String word : hypothesis.toLowerCase(Locale.ROOT).trim().split("\\s+")) {
            if (word.equals("gemini") || word.equals("jeminai") || word.equals("jemini")) return true;
        }
        return false;
    }
    private WakeWordResult() { }
}
