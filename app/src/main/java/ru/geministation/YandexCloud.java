package ru.geministation;

import android.content.Context;
import java.io.IOException;
import java.net.Proxy;
import java.util.concurrent.TimeUnit;
import okhttp3.FormBody;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.json.JSONArray;
import org.json.JSONObject;

/** Fixed-host experimental Yandex clients. Never log tokens or response bodies. */
final class YandexCloud {
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final OkHttpClient HTTP = new OkHttpClient.Builder().proxy(Proxy.NO_PROXY)
            .followRedirects(false).followSslRedirects(false).callTimeout(8, TimeUnit.SECONDS).build();
    static String safeError(Exception e) {
        String message = e.getMessage();
        return message != null && message.matches("HTTP [0-9]{3}") ? message : e.getClass().getSimpleName();
    }
    static JSONObject request(Request request, String stage) throws Exception {
        long started = android.os.SystemClock.elapsedRealtime();
        AppLog.add(stage + ": запрос начат");
        try (Response r = HTTP.newCall(request).execute()) {
            AppLog.add(stage + ": HTTP " + r.code() + " через " + (android.os.SystemClock.elapsedRealtime() - started) + " мс");
            if (!r.isSuccessful()) throw new IOException("HTTP " + r.code());
            JSONObject json = new JSONObject(HttpLimits.readUtf8(r.body(), 2 * 1024 * 1024));
            AppLog.add(stage + ": ответ обработан за " + (android.os.SystemClock.elapsedRealtime() - started) + " мс");
            return json;
        } catch (Exception e) {
            AppLog.add(stage + ": ошибка " + safeError(e) + " через " + (android.os.SystemClock.elapsedRealtime() - started) + " мс");
            throw e;
        }
    }
    static String musicToken(Context c) throws Exception {
        String saved = new SecretStore(c, "led_secrets").load();
        if (saved.isEmpty()) throw new IOException("Нет входа Яндекс для экрана");
        String x = new JSONObject(saved).getString("x_token");
        FormBody form = new FormBody.Builder().add("client_secret", "53bc75238f0c4d08a118e51fe9203300")
                .add("client_id", "23cabbbdc6cd418abb4b39c32c41195d").add("grant_type", "x-token")
                .add("access_token", x).build();
        String token = request(new Request.Builder().url("https://oauth.mobile.yandex.net/1/token").post(form).build(), "Яндекс: обмен QR-входа").getString("access_token");
        if (token.isEmpty()) throw new IOException("empty token");
        return token;
    }
    static final class Track {
        final String id, title, account;
        Track(String id, String title) { this(id, title, null); }
        Track(String id, String title, String account) { this.id = id; this.title = title; this.account = account; }
    }
    static String musicAccount(Context c) throws Exception {
        String config = new SecretStore(c, "led_secrets").load();
        if (config.isEmpty()) throw new IOException("no account");
        byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(config.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return android.util.Base64.encodeToString(digest, android.util.Base64.NO_WRAP);
    }
    static Track search(Context c, String query) throws Exception {
        if (!LinkPolicy.searchQuery(query)) throw new IOException("bad query");
        String account = musicAccount(c);
        String token = musicToken(c);
        if (!account.equals(musicAccount(c))) throw new IOException("account changed");
        HttpUrl url = HttpUrl.get("https://api.music.yandex.net/search").newBuilder()
                .addQueryParameter("text", query).addQueryParameter("type", "track").build();
        JSONObject result = request(new Request.Builder().url(url).header("Authorization", "OAuth " + token).build(), "Музыка: поиск").optJSONObject("result");
        if (!account.equals(musicAccount(c))) throw new IOException("account changed");
        JSONObject tracks = result == null ? null : result.optJSONObject("tracks");
        JSONArray items = tracks == null ? null : tracks.optJSONArray("results");
        AppLog.add("Музыка: кандидатов в ответе " + (items == null ? 0 : items.length()));
        Track found = parseSearch(result);
        return new Track(found.id, found.title, account);
    }
    static Track parseSearch(JSONObject result) throws Exception {
        JSONObject tracks = result == null ? null : result.optJSONObject("tracks");
        JSONArray items = tracks == null ? null : tracks.optJSONArray("results");
        if (items != null) for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null || !item.optBoolean("available", true)) continue;
            String id = item.optString("id", "");
            String title = item.optString("title", "").trim();
            if (!id.matches("[0-9]{1,20}") || title.isEmpty()) continue;
            JSONArray artists = item.optJSONArray("artists");
            JSONObject artist = artists == null ? null : artists.optJSONObject(0);
            String label = (artist == null ? "" : artist.optString("name", "") + " — ") + title;
            label = label.replaceAll("[\\p{Cntrl}]", " ");
            return new Track(id, label.substring(0, Math.min(label.length(), 160)));
        }
        throw new IOException("Трек не найден");
    }
    static JSONObject home(String token, String path, JSONObject body) throws Exception {
        if (!"/v1.0/user/info".equals(path) && !"/v1.0/devices/actions".equals(path)) throw new IOException("bad path");
        Request.Builder req = new Request.Builder().url("https://api.iot.yandex.net" + path).header("Authorization", "Bearer " + token);
        if (body != null) req.post(RequestBody.create(body.toString(), JSON));
        JSONObject response = request(req.build(), body == null ? "Умный дом: чтение" : "Умный дом: команда");
        String id = response.optString("request_id", "");
        if (id.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))
            AppLog.add("Умный дом: request_id " + id);
        return response;
    }
    private YandexCloud() { }
}
