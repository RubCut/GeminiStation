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
                if (part != null && containsWholeUrl(part.optString("text", ""), url)) return true;
            }
        }
        return false;
    }
    // Never authorize a URL just because it is a prefix or query-string fragment of another URL.
    // Deliberately conservative: if punctuation is ambiguous, ask the user for the exact URL again.
    static boolean containsWholeUrl(String text, String url) {
        int from = 0;
        while (from <= text.length() - url.length()) {
            int i = text.indexOf(url, from);
            if (i < 0) return false;
            int end = i + url.length();
            int left = i, right = end;
            while (left > 0 && "([{<\"«“".indexOf(text.charAt(left - 1)) >= 0) left--;
            while (right < text.length() && ")]}>\"»”".indexOf(text.charAt(right)) >= 0) right++;
            boolean before = left == 0 || Character.isWhitespace(text.charAt(left - 1));
            boolean after = right == text.length() || Character.isWhitespace(text.charAt(right));
            if (before && after) return true;
            from = i + 1;
        }
        return false;
    }
    private UserLinkAuthorization() { }
}
