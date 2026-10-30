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
    static String resultsUrl(String query) {
        if (!LinkPolicy.searchQuery(query)) throw new IllegalArgumentException("Invalid query");
        return new HttpUrl.Builder().scheme("https").host("www.youtube.com").addPathSegment("results")
                .addQueryParameter("search_query", query).build().toString();
    }
    private YoutubeSearchProtocol() { }
}
