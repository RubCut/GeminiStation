package ru.geministation;

import java.util.ArrayList;
import java.util.List;

/** Android engines impose a per-utterance limit; keep Unicode pairs and all text intact. */
final class TextChunks {
    static List<String> split(String text, int limit) {
        if (text == null || text.isEmpty() || limit < 2) throw new IllegalArgumentException("Invalid utterance");
        List<String> out = new ArrayList<>();
        for (int start = 0; start < text.length();) {
            int end = Math.min(text.length(), start + limit);
            if (end < text.length() && Character.isHighSurrogate(text.charAt(end - 1)) && Character.isLowSurrogate(text.charAt(end))) end--;
            if (end < text.length()) {
                int boundary = end;
                while (boundary > start + limit / 2 && !Character.isWhitespace(text.charAt(boundary - 1))) boundary--;
                if (boundary > start + limit / 2) end = boundary;
            }
            out.add(text.substring(start, end)); start = end;
        }
        return out;
    }
    private TextChunks() { }
}
