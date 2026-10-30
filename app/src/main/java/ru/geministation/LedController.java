package ru.geministation;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Base64;
import java.net.Proxy;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
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
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import org.json.JSONObject;

/** Optional, experimental local Station Max LED display. Never affects voice flow. */
final class LedController {
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
    private volatile int generation;
    private final AtomicBoolean inFlight = new AtomicBoolean();
    private String state;
    private Runnable refresh;
    private String cachedConfig, cachedToken;
    private long cachedUntil;
    private LedController(Context context) { this.context = context; }

    // Called on the main thread. GIFs have finite duration and are refreshed while active.
    void show(String next) {
        generation++;
        state = next;
        if (refresh != null) main.removeCallbacks(refresh);
        if (next == null) return;
        refresh = new Runnable() {
            public void run() {
                if (state == null) return;
                dispatch();
                main.postDelayed(this, 6500);
            }
        };
        refresh.run();
    }
    private void dispatch() {
        if (state == null || !inFlight.compareAndSet(false, true)) return;
        final int version = generation;
        final String frame = state;
        executor.execute(() -> {
            try { send(frame, version); }
            finally {
                inFlight.set(false);
                main.post(() -> { if (state != null && version != generation) dispatch(); });
            }
        });
    }
    void invalidate() {
        show(null);
        executor.execute(() -> { cachedConfig = null; cachedToken = null; cachedUntil = 0; });
    }
    void test() {
        if (state != null) { AppLog.add("LED: проверка доступна только вне диалога"); return; }
        show("thinking");
        int version = generation;
        main.postDelayed(() -> { if (generation == version) show(null); }, 30000);
    }
    private String responseBody(Call call) throws Exception {
        try (Response response = call.execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                AppLog.add("LED: Яндекс вернул HTTP " + response.code());
                throw new Exception("Cloud request failed");
            }
            return response.body().string();
        }
    }
    private String token(String json) throws Exception {
        long now = SystemClock.elapsedRealtime();
        if (json.equals(cachedConfig) && cachedToken != null && now < cachedUntil) return cachedToken;
        JSONObject cfg = new JSONObject(json);
        // These public mobile client parameters are also used by Station Max Studio.
        FormBody form = new FormBody.Builder().add("client_secret", "53bc75238f0c4d08a118e51fe9203300")
                .add("client_id", "23cabbbdc6cd418abb4b39c32c41195d").add("grant_type", "x-token")
                .add("access_token", cfg.getString("x_token")).build();
        JSONObject music = new JSONObject(responseBody(cloud.newCall(new Request.Builder()
                .url("https://oauth.mobile.yandex.net/1/token").post(form).build())));
        HttpUrl url = HttpUrl.parse("https://quasar.yandex.net/glagol/token").newBuilder()
                .addQueryParameter("device_id", cfg.getString("device_id"))
                .addQueryParameter("platform", cfg.getString("platform")).build();
        JSONObject glagol = new JSONObject(responseBody(cloud.newCall(new Request.Builder().url(url)
                .header("Authorization", "OAuth " + music.getString("access_token")).build())));
        if (!"ok".equals(glagol.optString("status"))) throw new Exception("Yandex token unavailable");
        String result = glagol.getString("token");
        if (result.isEmpty()) throw new Exception("Yandex token empty");
        cachedConfig = json; cachedToken = result; cachedUntil = now + 60000;
        return result;
    }
    private static JSONObject envelope(String token, JSONObject payload, String id) throws Exception {
        return new JSONObject().put("conversationToken", token).put("id", id).put("payload", payload)
                .put("sentTime", System.currentTimeMillis());
    }
    private static OkHttpClient localClient() throws Exception {
        // Glagol uses a self-signed certificate. This client is ONLY used for 127.0.0.1:1961,
        // never for the cloud APIs. A hostile local process could still impersonate that service.
        X509TrustManager trust = new X509TrustManager() {
            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            public void checkClientTrusted(X509Certificate[] chain, String auth) { }
            public void checkServerTrusted(X509Certificate[] chain, String auth) { }
        };
        SSLContext ssl = SSLContext.getInstance("TLS"); ssl.init(null, new javax.net.ssl.TrustManager[]{trust}, new SecureRandom());
        return new OkHttpClient.Builder().proxy(Proxy.NO_PROXY).sslSocketFactory(ssl.getSocketFactory(), trust)
                .hostnameVerifier((host, session) -> "127.0.0.1".equals(host))
                .connectTimeout(4, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).build();
    }
    private void send(String frame, int version) {
        String stage = "настройки";
        try {
            String cfg = new SecretStore(context, "led_secrets").load();
            if (cfg.isEmpty() || version != generation) return;
            stage = "авторизация Яндекса";
            String auth = token(cfg);
            if (version != generation) return;
            String helloId = UUID.randomUUID().toString(), commandId = UUID.randomUUID().toString();
            JSONObject hello = envelope(auth, new JSONObject().put("command", "softwareVersion"), helloId);
            String url = "http://127.0.0.1:6529/led/" + frame + ".gif";
            JSONObject payload = new JSONObject().put("command", "externalCommandBypass")
                    .put("data", Base64.encodeToString(LedProtocol.command(url), Base64.NO_WRAP));
            JSONObject command = envelope(auth, payload, commandId);
            CountDownLatch done = new CountDownLatch(1), commandSent = new CountDownLatch(1);
            AtomicBoolean sent = new AtomicBoolean(), error = new AtomicBoolean();
            stage = "локальное подключение";
            OkHttpClient local = localClient();
            WebSocket socket = local.newWebSocket(new Request.Builder().url("wss://127.0.0.1:1961/").build(), new WebSocketListener() {
                @Override public void onOpen(WebSocket ws, Response response) { ws.send(hello.toString()); }
                @Override public void onMessage(WebSocket ws, String message) {
                    try {
                        JSONObject reply = new JSONObject(message);
                        if (reply.has("error") || reply.has("errorText")) { error.set(true); commandSent.countDown(); done.countDown(); return; }
                        String id = reply.optString("id", reply.optString("requestId", ""));
                        if (!sent.get() && (id.equals(helloId) || reply.has("state") || reply.has("softwareVersion"))) {
                            if (version == generation && ws.send(command.toString())) sent.set(true);
                            else done.countDown();
                            commandSent.countDown();
                        } else if (sent.get() && id.equals(commandId)) {
                            String status = reply.optString("status", "");
                            if (!status.isEmpty() && !"OK".equals(status) && !"SUCCESS".equals(status)) error.set(true);
                            done.countDown();
                        }
                    } catch (Exception ignored) { }
                }
                @Override public void onFailure(WebSocket ws, Throwable t, Response response) { error.set(true); commandSent.countDown(); done.countDown(); }
                @Override public void onClosed(WebSocket ws, int code, String reason) { commandSent.countDown(); done.countDown(); }
            });
            commandSent.await(6, TimeUnit.SECONDS);
            if (sent.get()) done.await(2, TimeUnit.SECONDS);
            socket.close(1000, "done"); local.dispatcher().executorService().shutdown(); local.connectionPool().evictAll();
            if (version != generation) return;
            if (error.get() || !sent.get()) {
                cachedToken = null;
                AppLog.add("LED: локальная станция не приняла команду; проверьте настройки и порт 1961");
            } else AppLog.add("LED: команда отправлена (" + frame + "); показ на экране не подтверждён");
        } catch (Exception e) {
            if (version == generation) AppLog.add("LED: " + stage + ": ошибка " + e.getClass().getSimpleName() + "; проверьте настройки и сеть");
        }
    }
}
