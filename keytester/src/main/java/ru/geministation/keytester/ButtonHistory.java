package ru.geministation.keytester;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;

/** Bounded local history. Each event occupies one line and contains no entered characters. */
final class ButtonHistory {
    static final int LIMIT = 100;
    private final Deque<String> entries = new ArrayDeque<>();

    ButtonHistory(String stored) {
        if (stored != null && !stored.isEmpty()) {
            for (String line : stored.split("\n")) if (!line.isEmpty()) add(line);
        }
    }
    void add(String entry) {
        while (entries.size() >= LIMIT) entries.removeFirst();
        entries.addLast(oneLine(entry, 500));
    }
    void clear() { entries.clear(); }
    String latest() { return entries.peekLast(); }
    int size() { return entries.size(); }
    String serialize() {
        StringBuilder result = new StringBuilder();
        for (Iterator<String> it = entries.iterator(); it.hasNext();) {
            result.append(it.next());
            if (it.hasNext()) result.append('\n');
        }
        return result.toString();
    }
    static String oneLine(String text, int max) {
        if (text == null) return "—";
        String clean = text.replaceAll("[\\p{Cntrl}]", " ").trim();
        if (clean.isEmpty()) return "—";
        return clean.length() <= max ? clean : clean.substring(0, max);
    }
}
