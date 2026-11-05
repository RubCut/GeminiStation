package ru.geministation;

import okhttp3.HttpUrl;
import okhttp3.Request;

/** Read-only search request based on the unofficial yandex-music-api client. */
final class YandexMusicSearch {
    static Request request(String query, String token) {
        return request(query, token, "track");
    }
    static Request request(String query, String token, String type) {
        if (!LinkPolicy.searchQuery(query) || token == null || token.isEmpty() || !("track".equals(type) || "album".equals(type) || "playlist".equals(type)))
            throw new IllegalArgumentException("Invalid music search");
        HttpUrl url = HttpUrl.get("https://api.music.yandex.net/search").newBuilder()
                .addQueryParameter("text", query)
                .addQueryParameter("nocorrect", "False")
                .addQueryParameter("type", type)
                .addQueryParameter("page", "0")
                .addQueryParameter("playlist-in-best", "True").build();
        return new Request.Builder().url(url).get()
                .header("Authorization", "OAuth " + token)
                .header("X-Yandex-Music-Client", "YandexMusicAndroid/24023621")
                .header("User-Agent", "Yandex-Music-API")
                .header("Accept-Language", "ru").build();
    }
    private YandexMusicSearch() { }
}
