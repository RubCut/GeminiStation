package ru.geministation;

import java.io.IOException;
import okhttp3.HttpUrl;
import org.json.JSONArray;
import org.json.JSONObject;

/** Restrict search to real YouTube Data API video IDs, with a safe results-page fallback. */
final class YoutubeSearchProtocol {
    static String videoUrl(JSONObject json) throws IOException {
        JSONArray items = json.optJSONArray("items");
        JSONObject item = items == null ? null : items.optJSONObject(0);
        JSONObject id = item == null ? null : item.optJSONObject("id");
        String video = id == null ? "" : id.optString("videoId", "");
        if (!video.matches("[A-Za-z0-9_-]{11}")) throw new IOException("Видео не найдено");
        return "https://www.youtube.com/watch?v=" + video;
    }
    static boolean smartTube(String packageName) {
        return "org.smarttube.stable".equals(packageName) || "org.smarttube.beta".equals(packageName)
                || "app.smarttube".equals(packageName) || "app.smarttube.fdroid".equals(packageName)
                || "com.liskovsoft.smartyoutubetv2.tv".equals(packageName);
    }
    static String resultsUrl(String query, boolean playFirst) {
        if (!LinkPolicy.searchQuery(query)) throw new IllegalArgumentException("Invalid query");
        HttpUrl.Builder url = new HttpUrl.Builder().scheme("https").host("www.youtube.com").addPathSegment("results")
                .addQueryParameter("search_query", query);
        // SmartTube's public link handler interprets method=play as search + play first result.
        if (playFirst) url.addQueryParameter("method", "play");
        return url.build().toString();
    }
    private YoutubeSearchProtocol() { }
}
