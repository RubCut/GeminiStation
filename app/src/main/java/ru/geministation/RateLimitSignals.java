package ru.geministation;

import okhttp3.Response;
import org.json.JSONObject;

/** Only explicit quota signals should trigger a retry with another key. */
final class RateLimitSignals {
    static boolean apiError(int code, String status) {
        return code == 429 || "RESOURCE_EXHAUSTED".equals(status);
    }
    static boolean apiPayload(JSONObject payload) {
        JSONObject error = payload == null ? null : payload.optJSONObject("error");
        return error != null && apiError(error.optInt("code"), error.optString("status"));
    }
    static boolean httpResponse(Response response) {
        if (response == null) return false;
        if (response.code() == 429) return true;
        if (response.isSuccessful() || response.body() == null) return false;
        try { return apiPayload(new JSONObject(response.peekBody(64 * 1024).string())); }
        catch (Exception ignored) { return false; }
    }
    static boolean closeReason(String reason) {
        return reason != null && (reason.contains("RESOURCE_EXHAUSTED") || reason.matches("(?s).*\\b429\\b.*"));
    }
    private RateLimitSignals() { }
}
