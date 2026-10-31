package ru.geministation;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Base64;
import java.net.Proxy;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.SSLContext;
import javax.net.ssl.X509TrustManager;
import okhttp3.Call;
import okhttp3.FormBody;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.json.JSONObject;

/** Optional LED display. UI calls only schedule work; voice flow never waits for it. */
final class LedController {
    private static final long RETRY_MS = 3000;
    private static final int MAX_RETRIES = 3;
    private static LedController instance;
    static synchronized LedController get(Context context) {
        if (instance == null) instance = new LedController(context.getApplicationContext());
        return instance;
    }
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final OkHttpClient cloud = new OkHttpClient.Builder().proxy(Proxy.NO_PROXY)
            .callTimeout(15, TimeUnit.SECONDS).build();
    private final AtomicBoolean inFlight = new AtomicBoolean();
    private final AtomicBoolean tokenRejected = new AtomicBoolean();
    private volatile int generation;
    // The following fields are only touched from the main thread.
    private String active, desired;
    private Runnable idleClose, retry;
    private int retries;
    private long requestedAt;
    // These fields are only touched by the single LED executor.
    private String cachedConfig, cachedToken;
    private long cachedUntil;
    private LedConnection connection;
    private final Map<String, String> assetUrls = new HashMap<>();
    private String lastLoggedFrame;
    private long lastLoggedAt;
    private static final class MusicRequest {
        final String id; final long sentAt;
        MusicRequest(String id, long sentAt) { this.id = id; this.sentAt = sentAt; }
    }
    private final java.util.concurrent.atomic.AtomicReference<MusicRequest> pendingMusic = new java.util.concurrent.atomic.AtomicReference<>();
    private LedController(Context context) { this.context = context; }

    // Called on the main thread. Coalesce changes and avoid restarting the animation each cycle.
    void show(String next) {
        if (next == null) {
            if (active != null) clear();
            return;
        }
        active = next;
        change(next);
    }
    /** Replaces the current animation with a very short, finite blank GIF. No account changes. */
    void clear() {
        active = null;
        change("clear");
        final int version = generation;
        idleClose = () -> executor.execute(() -> { if (version == generation && connection != null) connection.close(); });
        main.postDelayed(idleClose, 10000);
    }
    private void change(String value) {
        if (idleClose != null) { main.removeCallbacks(idleClose); idleClose = null; }
        if (retry != null) { main.removeCallbacks(retry); retry = null; }
        retries = 0;
        desired = value; requestedAt = SystemClock.elapsedRealtime(); generation++;
        dispatch();
    }
    private void dispatch() {
        if (desired == null || !inFlight.compareAndSet(false, true)) return;
        if (retry != null) { main.removeCallbacks(retry); retry = null; }
        final int version = generation;
        final String frame = desired;
        final long started = requestedAt;
        executor.execute(() -> {
            boolean success = false;
            try { success = send(frame, version, started); }
            finally {
                final boolean sent = success;
                inFlight.set(false);
                main.post(() -> {
                    if (version != generation) {
                        if (desired != null) dispatch();
                    } else if (!sent) {
                        scheduleRetry(version);
                    }
                });
            }
        });
    }
    private void scheduleRetry(int version) {
        if (version != generation || desired == null || retry != null || retries >= MAX_RETRIES) return;
        retries++;
        retry = () -> {
            retry = null;
            if (version == generation) dispatch();
        };
        main.postDelayed(retry, RETRY_MS);
    }
    /** Discard old credentials and socket after account or station changes. */
    void invalidate() {
        active = null; desired = null; generation++; pendingMusic.set(null);
        if (idleClose != null) main.removeCallbacks(idleClose);
        if (retry != null) main.removeCallbacks(retry);
        idleClose = null; retry = null; retries = 0;
        executor.execute(() -> {
            cachedConfig = null; cachedToken = null; cachedUntil = 0; tokenRejected.set(false);
            if (connection != null) connection.close();
        });
    }
    void serverStarted() {
        // If a dialog started before the HTTP server was ready, resend its latest phase.
        if (active != null) show(active); else prewarm();
    }
    /** Warm the local connection in the background, if a station has been configured. */
    void prewarm() {
        final int version = generation;
        if (active == null) {
            if (idleClose != null) main.removeCallbacks(idleClose);
            idleClose = () -> executor.execute(() -> { if (version == generation && connection != null) connection.close(); });
            main.postDelayed(idleClose, 30000);
        }
        executor.execute(() -> {
            try {
                String cfg = new SecretStore(context, "led_secrets").load();
                if (cfg.isEmpty() || version != generation) return;
                String auth = token(cfg);
                if (version != generation) return;
                connect(auth);
            } catch (Exception e) {
                if (version == generation) AppLog.add("LED: фоновая подготовка недоступна (" + e.getClass().getSimpleName() + ")");
            }
        });
    }
    void test() {
        if (active != null) { AppLog.add("LED: проверка доступна только вне диалога"); return; }
        show("thinking");
        int version = generation;
        main.postDelayed(() -> { if (generation == version) show(null); }, 10000);
    }
    private String responseBody(Call call, String stage) throws Exception {
        long started = SystemClock.elapsedRealtime();
        AppLog.add(stage + ": запрос начат");
        try (Response response = call.execute()) {
            AppLog.add(stage + ": HTTP " + response.code() + " через " + (SystemClock.elapsedRealtime() - started) + " мс");
            if (!response.isSuccessful() || response.body() == null) throw new Exception("Cloud request failed");
            return HttpLimits.readUtf8(response.body(), 1024 * 1024);
        } catch (Exception e) {
            AppLog.add(stage + ": ошибка " + e.getClass().getSimpleName() + " через " + (SystemClock.elapsedRealtime() - started) + " мс");
            throw e;
        }
    }
    private String token(String json) throws Exception {
        if (tokenRejected.getAndSet(false)) { cachedToken = null; cachedUntil = 0; }
        long now = SystemClock.elapsedRealtime();
        if (json.equals(cachedConfig) && cachedToken != null && now < cachedUntil) return cachedToken;
        JSONObject cfg = new JSONObject(json);
        FormBody form = new FormBody.Builder().add("client_secret", "53bc75238f0c4d08a118e51fe9203300")
                .add("client_id", "23cabbbdc6cd418abb4b39c32c41195d").add("grant_type", "x-token")
                .add("access_token", cfg.getString("x_token")).build();
        JSONObject music = new JSONObject(responseBody(cloud.newCall(new Request.Builder()
                .url("https://oauth.mobile.yandex.net/1/token").post(form).build()), "LED: обмен QR-входа"));
        HttpUrl url = HttpUrl.parse("https://quasar.yandex.net/glagol/token").newBuilder()
                .addQueryParameter("device_id", cfg.getString("device_id"))
                .addQueryParameter("platform", cfg.getString("platform")).build();
        JSONObject glagol = new JSONObject(responseBody(cloud.newCall(new Request.Builder().url(url)
                .header("Authorization", "OAuth " + music.getString("access_token")).build()), "LED: запрос токена станции"));
        if (!"ok".equals(glagol.optString("status"))) throw new Exception("Yandex token unavailable");
        String result = glagol.getString("token");
        if (result.isEmpty()) throw new Exception("Yandex token empty");
        cachedConfig = json; cachedToken = result;
        // Keep this in memory only. On rejection, immediately fetch a new token.
        cachedUntil = SystemClock.elapsedRealtime() + TimeUnit.MINUTES.toMillis(10);
        AppLog.add("LED: токен подготовлен за " + (SystemClock.elapsedRealtime() - now) + " мс");
        return result;
    }
    private static JSONObject envelope(String token, JSONObject payload, String id) throws Exception {
        return new JSONObject().put("conversationToken", token).put("id", id).put("payload", payload)
                .put("sentTime", System.currentTimeMillis());
    }
    private static OkHttpClient localClient() throws Exception {
        // Trust the self-signed certificate ONLY for the explicitly hard-coded loopback socket.
        X509TrustManager trust = new X509TrustManager() {
            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            public void checkClientTrusted(X509Certificate[] chain, String auth) { }
            public void checkServerTrusted(X509Certificate[] chain, String auth) { }
        };
        SSLContext ssl = SSLContext.getInstance("TLS"); ssl.init(null, new javax.net.ssl.TrustManager[]{trust}, new SecureRandom());
        return new OkHttpClient.Builder().proxy(Proxy.NO_PROXY).sslSocketFactory(ssl.getSocketFactory(), trust)
                .hostnameVerifier((host, session) -> "127.0.0.1".equals(host))
                .connectTimeout(4, TimeUnit.SECONDS).readTimeout(0, TimeUnit.SECONDS).pingInterval(30, TimeUnit.SECONDS).build();
    }
    private boolean connect(String auth) throws Exception {
        if (connection == null) {
            connection = new LedConnection(localClient(), "wss://127.0.0.1:1961/", message -> {
                try {
                    JSONObject reply = new JSONObject(message);
                    return reply.has("state") || reply.has("softwareVersion") || reply.has("id") || reply.has("requestId");
                } catch (Exception ignored) { return false; }
            }, message -> {
                try {
                    JSONObject reply = new JSONObject(message);
                    recordMusicReply(reply);
                    if (reply.has("error") || reply.has("errorText")) {
                        tokenRejected.set(true); AppLog.add("LED: станция вернула ошибку; токен будет обновлён");
                        main.post(() -> scheduleRetry(generation));
                        return true;
                    }
                } catch (Exception ignored) { }
                return false;
            });
        }
        JSONObject hello = envelope(auth, new JSONObject().put("command", "softwareVersion"), UUID.randomUUID().toString());
        return connection.connect(hello.toString(), 6000);
    }
    private void recordMusicReply(JSONObject reply) {
        MusicRequest pending = pendingMusic.get();
        if (pending == null) return;
        String status = MusicDiagnostics.status(reply, pending.id);
        if (status == null || !pendingMusic.compareAndSet(pending, null)) return;
        AppLog.add("Музыка: ответ станции через " + (SystemClock.elapsedRealtime() - pending.sentAt)
                + " мс, статус " + status + "; воспроизведение не подтверждено");
    }
    /** Experimental playback on the already selected local station; no playback ACK is guaranteed. */
    void playMusic(String id, String account) {
        if (id == null || !id.matches("[0-9]{1,20}") || account == null) return;
        long queued = SystemClock.elapsedRealtime();
        AppLog.add("Музыка: команда поставлена в очередь после ответа ассистента");
        executor.execute(() -> {
            String stage = "проверка аккаунта";
            long started = SystemClock.elapsedRealtime();
            AppLog.add("Музыка: обработка началась через " + (started - queued) + " мс в очереди");
            try {
                SecretStore store = new SecretStore(context, "led_secrets");
                String cfg = store.load();
                if (cfg.isEmpty() || !account.equals(YandexCloud.musicAccount(context))) throw new Exception("account changed");
                stage = "токен станции";
                String auth = token(cfg);
                stage = "локальное соединение";
                if (!cfg.equals(store.load()) || !account.equals(YandexCloud.musicAccount(context)) || !connect(auth)) throw new Exception("not connected");
                stage = "отправка";
                JSONObject payload = new JSONObject().put("command", "playMusic").put("id", id).put("type", "track");
                if (!cfg.equals(store.load()) || !account.equals(YandexCloud.musicAccount(context))) throw new Exception("account changed");
                String requestId = UUID.randomUUID().toString();
                MusicRequest pending = new MusicRequest(requestId, SystemClock.elapsedRealtime());
                pendingMusic.set(pending);
                if (!connection.send(envelope(auth, payload, requestId).toString())) {
                    pendingMusic.compareAndSet(pending, null);
                    throw new Exception("not sent");
                }
                AppLog.add("Музыка: команда станции отправлена за " + (SystemClock.elapsedRealtime() - started) + " мс; воспроизведение не подтверждено");
                main.postDelayed(() -> {
                    if (pendingMusic.compareAndSet(pending, null)) AppLog.add("Музыка: ответа на команду нет 10 с; воспроизведение не подтверждено");
                }, 10000);
            } catch (Exception e) { AppLog.add("Музыка: этап " + stage + ", ошибка " + e.getClass().getSimpleName()
                    + " через " + (SystemClock.elapsedRealtime() - started) + " мс"); }
        });
    }
    private String assetUrl(String frame) throws Exception {
        String url = assetUrls.get(frame);
        if (url == null) {
            try (InputStream source = context.getAssets().open("led/" + frame + ".gif")) {
                url = LedAssetUrl.build(frame, source);
            }
            assetUrls.put(frame, url);
        }
        return url;
    }
    private boolean send(String frame, int version, long started) {
        String stage = "настройки";
        try {
            String cfg = new SecretStore(context, "led_secrets").load();
            if (cfg.isEmpty() || version != generation) return true;
            stage = "авторизация Яндекса";
            String auth = token(cfg);
            if (version != generation) return true;
            stage = "локальное подключение";
            if (!connect(auth)) throw new Exception("local connection failed");
            if (version != generation) return true;
            String url = assetUrl(frame);
            JSONObject payload = new JSONObject().put("command", "externalCommandBypass")
                    .put("data", Base64.encodeToString(LedProtocol.command(url), Base64.NO_WRAP));
            JSONObject command = envelope(auth, payload, UUID.randomUUID().toString());
            if (version != generation) return true;
            if (!connection.send(command.toString())) {
                connection.close(); throw new Exception("local send failed");
            }
            long now = SystemClock.elapsedRealtime();
            if (!frame.equals(lastLoggedFrame) || now - lastLoggedAt >= 10000) {
                AppLog.add("LED: команда " + frame + " отправлена через " + (now - started)
                        + " мс; показ на экране не подтверждён");
                lastLoggedFrame = frame; lastLoggedAt = now;
            }
            return true;
        } catch (Exception e) {
            if (version == generation) AppLog.add("LED: " + stage + ": ошибка " + e.getClass().getSimpleName() + "; проверьте настройки и сеть");
            return false;
        }
    }
}
