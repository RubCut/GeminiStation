package ru.geministation;

/** Only explicit quota signals should trigger a retry with another key. */
final class RateLimitSignals {
    static boolean apiError(int code, String status) {
        return code == 429 || "RESOURCE_EXHAUSTED".equals(status);
    }
    static boolean closeReason(String reason) {
        return reason != null && (reason.contains("RESOURCE_EXHAUSTED") || reason.matches("(?s).*\\b429\\b.*"));
    }
    private RateLimitSignals() { }
}
