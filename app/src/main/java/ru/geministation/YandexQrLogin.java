package ru.geministation;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.SystemClock;
import android.util.Base64;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.common.BitMatrix;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.Proxy;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import okhttp3.Cookie;
import okhttp3.CookieJar;
import okhttp3.FormBody;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.json.JSONArray;
import org.json.JSONObject;

/** Experimental QR flow based on Station Max Studio and AlexxIT/YandexStation.
 * All methods are serialized; tokens and cookies never leave this process or go to logs. */
final class YandexQrLogin {
    private static final String PASSPORT = "https://passport.yandex.ru";
    private static final String MOBILE = "https://mobileproxy.passport.yandex.net";
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/128.0 Safari/537.36";
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final Pattern CSRF = Pattern.compile("__CSRF__\\s*=\\s*['\"]([^'\"]+)['\"]", Pattern.CASE_INSENSITIVE);
    private final Context context;
    private Session session;
    private final java.util.concurrent.ScheduledExecutorService timer = java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
    private java.util.concurrent.ScheduledFuture<?> expiry;

    private static final class Jar implements CookieJar {
        private final List<Cookie> cookies = new ArrayList<>();
        @Override public synchronized void saveFromResponse(HttpUrl url, List<Cookie> incoming) {
            for (Cookie c : incoming) {
                for (int i = cookies.size() - 1; i >= 0; i--) {
                    Cookie old = cookies.get(i);
                    if (old.name().equals(c.name()) && old.domain().equals(c.domain()) && old.path().equals(c.path())) cookies.remove(i);
                }
                if (c.expiresAt() > System.currentTimeMillis() && cookies.size() < 150) cookies.add(c);
            }
        }
        @Override public synchronized List<Cookie> loadForRequest(HttpUrl url) {
            List<Cookie> result = new ArrayList<>();
            for (Cookie c : cookies) if (c.expiresAt() > System.currentTimeMillis() && c.matches(url)) result.add(c);
            return result;
        }
        synchronized String yandexCookies() {
            StringBuilder out = new StringBuilder();
            for (Cookie c : cookies) {
                String domain = c.domain();
                if (!(domain.equals("yandex.ru") || domain.endsWith(".yandex.ru")) || c.expiresAt() <= System.currentTimeMillis()) continue;
                if (out.length() > 0) out.append("; ");
                out.append(c.name()).append('=').append(c.value());
            }
            return out.toString();
        }
    }
    private static final class Session {
        final String id = UUID.randomUUID().toString();
        final Jar jar = new Jar();
        final OkHttpClient client = new OkHttpClient.Builder().proxy(Proxy.NO_PROXY).cookieJar(jar)
                .followRedirects(false).followSslRedirects(false).callTimeout(15, java.util.concurrent.TimeUnit.SECONDS).build();
        long expiresAt, lastPoll;
        String csrf, authJson, token;
        final JSONArray stations = new JSONArray();
    }
    YandexQrLogin(Context context) { this.context = context.getApplicationContext(); }

    private static String readBody(Response response) throws Exception {
        if (response.body() == null) return "";
        if (response.body().contentLength() > 1024 * 1024) throw new IOException("response too large");
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (java.io.InputStream in = response.body().byteStream()) {
            byte[] part = new byte[8192]; int count;
            while ((count = in.read(part)) != -1) {
                if (buffer.size() + count > 1024 * 1024) throw new IOException("response too large");
                buffer.write(part, 0, count);
            }
        }
        return new String(buffer.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
    }
    private static String read(Session s, Request request, boolean acceptRedirect) throws Exception {
        try (Response response = s.client.newCall(request).execute()) {
            if (!(response.isSuccessful() || (acceptRedirect && response.code() >= 300 && response.code() < 400)))
                throw new IOException("HTTP " + response.code());
            return readBody(response);
        }
    }
    private static String getFollowingYandex(Session s, String url, boolean needBody) throws Exception {
        for (int redirects = 0; redirects <= 5; redirects++) {
            if (!YandexUrlPolicy.allowed(url)) throw new IOException("invalid redirect host");
            try (Response response = s.client.newCall(request(url).header("Accept", "text/html").header("Accept-Language", "ru-RU,ru;q=0.9").header("Cache-Control", "no-cache").build()).execute()) {
                if (response.isSuccessful()) return needBody ? readBody(response) : "";
                if (response.code() < 300 || response.code() >= 400) throw new IOException("HTTP " + response.code());
                String location = response.header("Location");
                HttpUrl next = location == null ? null : response.request().url().resolve(location);
                if (next == null) throw new IOException("redirect without location");
                url = next.toString();
            }
        }
        throw new IOException("too many redirects");
    }
    private static Request.Builder request(String url) { return new Request.Builder().url(url).header("User-Agent", UA); }
    private static RequestBody form(String... pairs) {
        FormBody.Builder form = new FormBody.Builder();
        for (int i = 0; i < pairs.length; i += 2) form.add(pairs[i], pairs[i+1]);
        return form.build();
    }
    private static JSONObject postJson(Session s, String url, RequestBody body, String csrf) throws Exception {
        Request.Builder builder = request(url).post(body);
        if (csrf != null) builder.header("X-CSRF-Token", csrf);
        return new JSONObject(read(s, builder.build(), false));
    }
    private static String qr(String value) throws Exception {
        if (value == null || value.length() > 2048 || !YandexUrlPolicy.allowed(value)) throw new IOException("invalid QR link");
        EnumMap<EncodeHintType,Object> hints = new EnumMap<>(EncodeHintType.class);
        hints.put(EncodeHintType.MARGIN, 2); hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
        BitMatrix matrix = new QRCodeWriter().encode(value, BarcodeFormat.QR_CODE, 300, 300, hints);
        int[] pixels = new int[300 * 300];
        for (int y = 0; y < 300; y++) for (int x = 0; x < 300; x++) pixels[y * 300 + x] = matrix.get(x, y) ? 0xff000000 : 0xffffffff;
        Bitmap bitmap = Bitmap.createBitmap(pixels, 300, 300, Bitmap.Config.ARGB_8888);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try { if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) throw new IOException("QR encoding"); }
        finally { bitmap.recycle(); }
        return Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP);
    }
    synchronized JSONObject start() throws Exception {
        clear();
        Session s = new Session();
        session = s;
        try {
        String html = getFollowingYandex(s, PASSPORT + "/pwl-yandex", true);
        Matcher match = CSRF.matcher(html);
        if (!match.find()) throw new IOException("captcha or changed login page");
        s.csrf = match.group(1);
        JSONObject auth = postJson(s, PASSPORT + "/pwl-yandex/api/passport/auth/password/submit",
                RequestBody.create(new JSONObject().put("retpath", PASSPORT + "/").toString(), JSON), s.csrf);
        String track = auth.getString("track_id");
        JSONObject result = postJson(s, PASSPORT + "/pwl-yandex/api/passport/auth/magic/code",
                form("location_id", "0", "magic_track_id", track, "track_id", ""), s.csrf);
        String image = qr(result.getString("link"));
        s.authJson = auth.toString(); s.expiresAt = SystemClock.elapsedRealtime() + 180000;
        expiry = timer.schedule(() -> { synchronized (YandexQrLogin.this) { if (session == s) clear(); } }, 180000, java.util.concurrent.TimeUnit.MILLISECONDS);
        AppLog.add("Яндекс: QR-вход начат");
        return new JSONObject().put("id", s.id).put("qr", image);
        } catch (Exception e) { clear(); throw e; }
    }
    private Session session(String id) throws Exception {
        if (session == null || !session.id.equals(id)) throw new IOException("no active session");
        if (SystemClock.elapsedRealtime() >= session.expiresAt) { clear(); throw new IOException("expired"); }
        return session;
    }
    synchronized JSONObject poll(String id) throws Exception {
        Session s = session(id);
        if (s.token != null) return ready(s);
        long now = SystemClock.elapsedRealtime();
        if (now - s.lastPoll < 1500) return new JSONObject().put("state", "waiting");
        s.lastPoll = now;
        JSONObject status = postJson(s, PASSPORT + "/pwl-yandex/api/passport/auth/magic/code/status",
                RequestBody.create(s.authJson, JSON), s.csrf);
        if (!"otp_auth_finished".equals(status.optString("state"))) return new JSONObject().put("state", "waiting");
        read(s, request(PASSPORT + "/pwl-yandex/api/passport/sessions/get_session")
                .header("X-CSRF-Token", s.csrf).post(form("track_id", status.getString("trackId"))).build(), false);
        String cookies = s.jar.yandexCookies();
        if (cookies.isEmpty()) throw new IOException("no session cookie");
        JSONObject tokenData = new JSONObject(read(s, request(MOBILE + "/1/bundle/oauth/token_by_sessionid")
                .header("Ya-Client-Host", "passport.yandex.ru").header("Ya-Client-Cookie", cookies)
                .post(form("client_id", "c0ebe342af7d48fbbbfcf2d2eedb8f9e", "client_secret", "ad0a908f0aa341a182a37ecd75bc319e")).build(), false));
        String token = tokenData.optString("access_token", "");
        if (token.isEmpty() || token.length() > 4096) throw new IOException("token unavailable");
        listStations(s, token);
        s.token = token; s.authJson = null; s.csrf = null;
        AppLog.add("Яндекс: вход подтверждён, найдено станций: " + s.stations.length());
        return ready(s);
    }
    private static JSONObject ready(Session s) throws Exception {
        JSONArray list = new JSONArray();
        for (int i = 0; i < s.stations.length(); i++) {
            JSONObject station = s.stations.getJSONObject(i);
            list.put(new JSONObject().put("name", station.getString("name")).put("house", station.getString("house")));
        }
        return new JSONObject().put("state", "ready").put("stations", list);
    }
    private static void listStations(Session s, String token) throws Exception {
        JSONObject auth = new JSONObject(read(s, request(MOBILE + "/1/bundle/auth/x_token/")
                .header("Ya-Consumer-Authorization", "OAuth " + token)
                .post(form("type", "x-token", "retpath", "https://www.yandex.ru")).build(), false));
        if (!"ok".equals(auth.optString("status"))) throw new IOException("invalid token");
        HttpUrl host = HttpUrl.parse(auth.getString("passport_host"));
        if (host == null || !host.isHttps() || !("passport.yandex.ru".equals(host.host()) || "passport.yandex.kz".equals(host.host())) || host.port() != 443)
            throw new IOException("invalid passport host");
        HttpUrl sessionUrl = host.newBuilder().encodedPath("/auth/session/").addQueryParameter("track_id", auth.getString("track_id")).build();
        getFollowingYandex(s, sessionUrl.toString(), false);
        JSONObject devices = new JSONObject(read(s, request("https://iot.quasar.yandex.ru/m/v3/user/devices").build(), false));
        if (!"ok".equals(devices.optString("status"))) throw new IOException("device list unavailable");
        JSONArray homes = devices.optJSONArray("households");
        if (homes == null) throw new IOException("no homes");
        for (int i = 0; i < homes.length() && s.stations.length() < 100; i++) {
            JSONObject home = homes.optJSONObject(i); if (home == null) continue;
            JSONArray all = home.optJSONArray("all"); if (all == null) continue;
            for (int j = 0; j < all.length() && s.stations.length() < 100; j++) {
                JSONObject d = all.optJSONObject(j); if (d == null) continue;
                JSONObject sharing = d.optJSONObject("sharing_info"); if (sharing != null && sharing.length() > 0) continue;
                JSONObject info = d.optJSONObject("quasar_info"); if (info == null) continue;
                String device = info.optString("device_id", ""), platform = info.optString("platform", "");
                if (!device.matches("[A-Za-z0-9._:-]{1,200}") || !platform.matches("[A-Za-z0-9._:-]{1,200}")
                        || !d.optString("type", "").startsWith("devices.types.smart_speaker") || !d.has("capabilities")) continue;
                String name = d.optString("name", "Станция"), house = home.optString("name", "");
                s.stations.put(new JSONObject().put("name", name.substring(0, Math.min(120, name.length())))
                        .put("house", house.substring(0, Math.min(80, house.length())))
                        .put("device", device).put("platform", platform));
            }
        }
        if (s.stations.length() == 0) throw new IOException("no stations");
    }
    synchronized void select(String id, int index) throws Exception {
        Session s = session(id);
        if (s.token == null || index < 0 || index >= s.stations.length()) throw new IOException("invalid selection");
        JSONObject selected = s.stations.getJSONObject(index);
        new SecretStore(context, "led_secrets").save(new JSONObject().put("x_token", s.token)
                .put("device_id", selected.getString("device")).put("platform", selected.getString("platform")).toString());
        clear();
        AppLog.add("Яндекс: станция для LED выбрана");
    }
    synchronized void cancel(String id) { if (session != null && session.id.equals(id)) clear(); }
    void closeAsync() { timer.execute(this::clear); timer.shutdown(); }
    synchronized void clear() {
        if (expiry != null) { expiry.cancel(false); expiry = null; }
        if (session != null) {
            session.client.dispatcher().cancelAll(); session.client.connectionPool().evictAll();
            session = null;
        }
    }
}
