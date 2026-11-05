package ru.geministation;

import org.json.JSONObject;

/** Match only our own request, emit fixed strings, and never expose response fields verbatim. */
final class MusicDiagnostics {
    static String status(JSONObject reply, String requestId) {
        if (reply == null || requestId == null || requestId.isEmpty() ||
                !(requestId.equals(reply.optString("requestId", "")) || requestId.equals(reply.optString("id", "")))) return null;
        if (reply.has("error") || reply.has("errorText")) return "error";
        String value = reply.optString("status", "");
        if ("ok".equalsIgnoreCase(value)) return "ok";
        if ("error".equalsIgnoreCase(value)) return "error";
        if ("success".equalsIgnoreCase(value)) return "success";
        return "не указан";
    }
    private MusicDiagnostics() { }
}
