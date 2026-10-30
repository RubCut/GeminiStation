package ru.geministation;

import org.json.JSONArray;
import org.json.JSONObject;

/** Do not treat URLs invented by the model or app labels as a user request. */
final class UserLinkAuthorization {
    static boolean presentInUserTurns(String url, JSONArray history) {
        if (!LinkPolicy.allowed(url) || history == null) return false;
        for (int i = 0; i < history.length(); i++) {
            JSONObject turn = history.optJSONObject(i);
            if (turn == null || !"user".equals(turn.optString("role"))) continue;
            JSONArray parts = turn.optJSONArray("parts");
            if (parts == null) continue;
            for (int j = 0; j < parts.length(); j++) {
                JSONObject part = parts.optJSONObject(j);
                if (part != null && part.optString("text", "").contains(url)) return true;
            }
        }
        return false;
    }
    private UserLinkAuthorization() { }
}
