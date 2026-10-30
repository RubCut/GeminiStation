package ru.geministation;

/** Merges finalized segments, while keeping interim hypotheses replaceable. */
final class TranscriptAccumulator {
    private String finalized = "";
    private String interim = "";
    synchronized String partial(String value) {
        interim = value == null ? "" : value.trim();
        return merge(finalized, interim);
    }
    synchronized String addFinal(String value) {
        finalized = merge(finalized, value == null ? "" : value.trim());
        interim = "";
        return finalized;
    }
    synchronized String best() { return merge(finalized, interim).trim(); }
    synchronized boolean hasFinal() { return !finalized.isEmpty(); }
    private static String merge(String previous, String next) {
        if (next.isEmpty()) return previous;
        if (previous.isEmpty()) return next;
        if (next.startsWith(previous)) return next;
        if (previous.equals(next) || previous.endsWith(next)) return previous;
        int max = Math.min(previous.length(), next.length());
        for (int n = max; n >= 2; n--) {
            if (previous.regionMatches(previous.length() - n, next, 0, n)) return previous + next.substring(n);
        }
        boolean punctuation = ",.!?:;»)]}".indexOf(next.charAt(0)) >= 0;
        return previous + (punctuation ? "" : " ") + next;
    }
}
