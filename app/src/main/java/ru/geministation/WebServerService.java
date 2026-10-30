package ru.geministation;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.os.Handler;
import android.os.Looper;
import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import fi.iki.elonen.NanoHTTPD;
import org.json.JSONObject;

/** Runs independently of the activity while the app process/service remains alive. */
public final class WebServerService extends Service {
    private static final int PORT = 6529;
    private static final String CHANNEL = "gemini_web";
    private static volatile boolean running;
    private Server server;

    static boolean isRunning() { return running; }
    static String address() {
        try {
            for (NetworkInterface net : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!net.isUp() || net.isLoopback()) continue;
                for (java.net.InetAddress ip : Collections.list(net.getInetAddresses())) {
                    if (ip instanceof Inet4Address && !ip.isLoopbackAddress() && !ip.isLinkLocalAddress())
                        return "http://" + ip.getHostAddress() + ":" + PORT;
                }
            }
        } catch (Exception ignored) { }
        return "http://<IP-станции>:" + PORT;
    }
    @Override public void onCreate() {
        super.onCreate();
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL, "Веб-интерфейс Gemini Station", NotificationManager.IMPORTANCE_LOW));
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pending = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new Notification.Builder(this, CHANNEL)
                .setContentTitle("Gemini Station")
                .setContentText("Веб-интерфейс работает на порту " + PORT)
                .setSmallIcon(android.R.drawable.ic_dialog_info).setContentIntent(pending).build();
        startForeground(6529, notification);
        try {
            server = new Server(getApplicationContext());
            server.start(5000, false);
            running = true;
            AppLog.add("Веб-сервер запущен на порту " + PORT);
            WakeWordController.get(this).serviceStarted();
            LedController.get(this).serverStarted();
        } catch (Exception e) {
            running = false;
            AppLog.add("Веб-сервер: ошибка запуска " + e.getClass().getSimpleName() + "; порт занят или недоступен");
            stopSelf();
        }
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) { return START_STICKY; }
    @Override public void onDestroy() {
        WakeWordController.get(this).serviceStopped();
        if (server != null) { server.closeAuth(); server.stop(); }
        running = false;
        AppLog.add("Веб-сервер остановлен");
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }

    private static final class Server extends NanoHTTPD {
        private final Context context;
        private final String page;
        private final YandexQrLogin qrLogin;
        private final GeminiClient sampleClient;
        private final AtomicBoolean sampleInProgress = new AtomicBoolean();
        private String lastLedAsset;
        private long lastLedAssetAt;
        void closeAuth() { qrLogin.closeAsync(); }
        Server(Context context) throws Exception {
            super(PORT); // Listen on all interfaces. Use ADB forwarding on untrusted networks.
            this.context = context;
            this.sampleClient = new GeminiClient(context);
            this.qrLogin = new YandexQrLogin(context);
            try (InputStream in = context.getAssets().open("index.html"); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096];
                int n;
                while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
                page = out.toString("UTF-8");
            }
        }
        // NanoHTTPD 2.3.1 finishes but does not close its GZIPOutputStream.
        // Disabling gzip avoids leaking its native Deflater for every web UI poll.
        @Override protected boolean useGzipWhenAccepted(Response response) { return false; }
        private Response reply(Response.Status status, String mime, String body) {
            Response response = newFixedLengthResponse(status, mime, body);
            response.addHeader("Cache-Control", "no-store");
            response.addHeader("X-Content-Type-Options", "nosniff");
            response.addHeader("X-Frame-Options", "DENY");
            response.addHeader("Content-Security-Policy", "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; img-src data:; media-src 'self' blob:; connect-src 'self'; base-uri 'none'; frame-ancestors 'none'; form-action 'none'");
            return response;
        }
        private Response json(Response.Status status, String message) {
            try { return reply(status, "application/json; charset=utf-8", new JSONObject().put("message", message).toString()); }
            catch (Exception e) { return reply(Response.Status.INTERNAL_ERROR, "text/plain", "Error"); }
        }
        @Override public Response serve(IHTTPSession session) {
            String path = session.getUri();
            String host = session.getHeaders().get("host");
            if (!WebSecurity.permittedHost(host)) return json(Response.Status.FORBIDDEN, "Недопустимый адрес");
            if (session.getMethod() == Method.GET && "/".equals(path)) return reply(Response.Status.OK, "text/html; charset=utf-8", page);
            // Only the station itself may fetch the fixed images.
            if (session.getMethod() == Method.GET && path.matches("/led/(listening|thinking|speaking|clear)\\.gif")) {
                if (!"127.0.0.1".equals(session.getRemoteIpAddress())) return json(Response.Status.FORBIDDEN, "Только локально");
                try {
                    synchronized (this) {
                        long now = android.os.SystemClock.elapsedRealtime();
                        if (!path.equals(lastLedAsset) || now - lastLedAssetAt >= 10000) {
                            AppLog.add("LED: станция запросила " + path.substring(5));
                            lastLedAsset = path; lastLedAssetAt = now;
                        }
                    }
                    InputStream gif = context.getAssets().open(path.substring(1));
                    Response response = newFixedLengthResponse(Response.Status.OK, "image/gif", gif, gif.available());
                    response.addHeader("Cache-Control", "no-store");
                    return response;
                } catch (Exception e) { return json(Response.Status.NOT_FOUND, "Нет изображения"); }
            }
            if (!path.startsWith("/api/")) return json(Response.Status.NOT_FOUND, "Не найдено");
            try {
                if (session.getMethod() == Method.GET && "/api/logs".equals(path)) {
                    JSONObject result = new JSONObject().put("logs", AppLog.snapshot()).put("model", AppSettings.model(context))
                            .put("voice", AppSettings.voice(context)).put("voices", new org.json.JSONArray(TtsVoices.ALL))
                            .put("apiKeys", ApiKeyVault.get(context).metadata())
                            .put("buttonServiceConnected", AssistantButtonService.isConnected())
                            .put("buttonEnabled", AppSettings.buttonEnabled(context))
                            .put("wakeEnabled", AppSettings.wakeEnabled(context))
                            .put("wakeStatus", WakeWordController.get(context).status())
                            .put("wakeSensitivity", AppSettings.wakeSensitivity(context))
                            .put("wakeRms", WakeWordController.get(context).rms())
                            .put("wakePeak", WakeWordController.get(context).peak())
                            .put("dnsEnabled", AppSettings.dnsEnabled(context))
                            .put("dnsBypassVpn", AppSettings.dnsBypassVpn(context))
                            .put("dnsServer", AppSettings.dnsServer(context))
                            .put("vpnActive", VpnStatus.active(context))
                            .put("systemPrompt", AppSettings.systemPrompt(context))
                            .put("defaultSystemPrompt", SystemPrompt.DEFAULT);
                    return reply(Response.Status.OK, "application/json; charset=utf-8", result.toString());
                }
                if (session.getMethod() == Method.POST && "/api/voice/sample".equals(path)) {
                    if (!WebSecurity.permittedWrite(host, session.getHeaders().get("origin"), session.getHeaders().get("x-gemini-station")))
                        return json(Response.Status.FORBIDDEN, "Запрос отклонён");
                    String lengthHeader = session.getHeaders().get("content-length");
                    long length = lengthHeader == null ? -1 : Long.parseLong(lengthHeader);
                    if (length < 1 || length > 512 || session.getHeaders().containsKey("transfer-encoding"))
                        return json(Response.Status.BAD_REQUEST, "Недопустимый размер запроса");
                    Map<String, String> files = new HashMap<>(); session.parseBody(files);
                    String voice = new JSONObject(files.get("postData")).optString("voice", "");
                    if (!TtsVoices.valid(voice)) return json(Response.Status.BAD_REQUEST, "Неизвестный голос");
                    if (!sampleInProgress.compareAndSet(false, true))
                        return json(Response.Status.SERVICE_UNAVAILABLE, "Другой образец уже загружается; попробуйте немного позже");
                    try {
                        ApiKeyVault vault = ApiKeyVault.get(context);
                        if (vault.keys().isEmpty()) return json(Response.Status.BAD_REQUEST, "Сначала сохраните API-ключ Gemini");
                        AppLog.add("TTS: запрос образца голоса " + voice);
                        PcmAudio sample = vault.run(KeyRotationDomains.Domain.TTS, () -> true, key -> sampleClient.synthesize(key,
                                "Привет! Я голосовой помощник Gemini. Так звучит мой голос на русском языке.", voice));
                        byte[] wav = sample.toWav();
                        Response response = newFixedLengthResponse(Response.Status.OK, "audio/wav", new ByteArrayInputStream(wav), wav.length);
                        response.addHeader("Cache-Control", "no-store");
                        response.addHeader("X-Content-Type-Options", "nosniff");
                        return response;
                    } catch (Exception e) {
                        String detail = e.getMessage();
                        String safe = detail != null && detail.matches("Gemini TTS: HTTP [0-9]{3}") ? " (" + detail + ")" : "";
                        AppLog.add("TTS: не удалось получить образец голоса " + voice + safe);
                        return json(Response.Status.INTERNAL_ERROR, "Не удалось получить образец Gemini" + safe + ". Проверьте ключ, сеть и доступ к TTS.");
                    } finally { sampleInProgress.set(false); }
                }
                if (session.getMethod() == Method.POST && path.startsWith("/api/yandex/")) {
                    if (!WebSecurity.permittedWrite(host, session.getHeaders().get("origin"), session.getHeaders().get("x-gemini-station")))
                        return json(Response.Status.FORBIDDEN, "Запрос отклонён");
                    String lengthHeader = session.getHeaders().get("content-length");
                    long length = lengthHeader == null ? -1 : Long.parseLong(lengthHeader);
                    if (length < 1 || length > 2048 || session.getHeaders().containsKey("transfer-encoding"))
                        return json(Response.Status.BAD_REQUEST, "Недопустимый размер запроса");
                    Map<String, String> files = new HashMap<>(); session.parseBody(files);
                    JSONObject data = new JSONObject(files.get("postData"));
                    String id = data.optString("id", "");
                    try {
                        JSONObject result;
                        switch (path) {
                            case "/api/yandex/start": result = qrLogin.start(); break;
                            case "/api/yandex/poll": result = qrLogin.poll(id); break;
                            case "/api/yandex/select":
                                qrLogin.select(id, data.optInt("index", -1));
                                new Handler(Looper.getMainLooper()).post(() -> {
                                    LedController led = LedController.get(context); led.invalidate(); led.prewarm();
                                });
                                result = new JSONObject().put("message", "Станция выбрана"); break;
                            case "/api/yandex/cancel": qrLogin.cancel(id); result = new JSONObject().put("message", "Вход отменён"); break;
                            default: return json(Response.Status.NOT_FOUND, "Не найдено");
                        }
                        return reply(Response.Status.OK, "application/json; charset=utf-8", result.toString());
                    } catch (Exception e) {
                        qrLogin.cancel(id);
                        String detail = e.getMessage();
                        String safeDetail = detail != null && detail.matches("HTTP [0-9]{3}") ? detail : e.getClass().getSimpleName();
                        AppLog.add("Яндекс: ошибка QR-входа на этапе " + path.substring(path.lastIndexOf('/') + 1) + " (" + safeDetail + ")");
                        return json(Response.Status.BAD_REQUEST, "Не удалось выполнить вход. Яндекс мог запросить капчу или изменить API; попробуйте ещё раз либо импортируйте настройки Studio.");
                    }
                }
                if (session.getMethod() == Method.POST && ("/api/led".equals(path) || "/api/led/test".equals(path) || "/api/led/clear".equals(path))) {
                    // LAN access is passwordless by design. Keep the same-origin/Host protections.
                    if (!WebSecurity.permittedWrite(host, session.getHeaders().get("origin"), session.getHeaders().get("x-gemini-station")))
                        return json(Response.Status.FORBIDDEN, "Запрос отклонён");
                    String lengthHeader = session.getHeaders().get("content-length");
                    long length = lengthHeader == null ? -1 : Long.parseLong(lengthHeader);
                    if (length < 1 || length > 8192 || session.getHeaders().containsKey("transfer-encoding"))
                        return json(Response.Status.BAD_REQUEST, "Недопустимый размер запроса");
                    Map<String, String> files = new HashMap<>(); session.parseBody(files);
                    JSONObject data = new JSONObject(files.get("postData"));
                    SecretStore store = new SecretStore(context, "led_secrets");
                    if ("/api/led/test".equals(path) || "/api/led/clear".equals(path)) {
                        if (store.load().isEmpty()) return json(Response.Status.BAD_REQUEST, "Сначала войдите в Яндекс и выберите станцию или импортируйте настройки");
                        if ("/api/led/test".equals(path)) {
                            new Handler(Looper.getMainLooper()).post(() -> LedController.get(context).test());
                            return json(Response.Status.OK, "Команда проверки запущена; смотрите журнал и экран станции");
                        }
                        new Handler(Looper.getMainLooper()).post(() -> LedController.get(context).clear());
                        return json(Response.Status.OK, "Запрошен возврат к часам; аккаунт сохранён");
                    }
                    qrLogin.clear();
                    boolean deleted = data.optBoolean("delete", false);
                    if (deleted) { store.clear(); AppLog.add("LED: настройки удалены"); }
                    else {
                        String token = data.optString("x_token", "").trim();
                        String device = data.optString("device_id", "").trim();
                        String platform = data.optString("platform", "").trim();
                        if (token.isEmpty() || token.length() > 4096 || !device.matches("[A-Za-z0-9._:-]{1,200}") || !platform.matches("[A-Za-z0-9._:-]{1,200}"))
                            return json(Response.Status.BAD_REQUEST, "В файле нет подходящих x_token, device_id и platform");
                        store.save(new JSONObject().put("x_token", token).put("device_id", device).put("platform", platform).toString());
                        AppLog.add("LED: настройки импортированы через веб-интерфейс");
                    }
                    new Handler(Looper.getMainLooper()).post(() -> {
                        LedController led = LedController.get(context); led.invalidate(); if (!deleted) led.prewarm();
                    });
                    return json(Response.Status.OK, deleted ? "Настройки LED удалены" : "Настройки LED сохранены");
                }
                if (session.getMethod() == Method.POST && "/api/keys".equals(path)) {
                    if (!WebSecurity.permittedWrite(host, session.getHeaders().get("origin"), session.getHeaders().get("x-gemini-station")))
                        return json(Response.Status.FORBIDDEN, "Запрос отклонён");
                    String lengthHeader = session.getHeaders().get("content-length");
                    long length = lengthHeader == null ? -1 : Long.parseLong(lengthHeader);
                    if (length < 1 || length > 2048 || session.getHeaders().containsKey("transfer-encoding"))
                        return json(Response.Status.BAD_REQUEST, "Недопустимый размер запроса");
                    Map<String, String> files = new HashMap<>(); session.parseBody(files);
                    JSONObject data = new JSONObject(files.get("postData"));
                    ApiKeyVault vault = ApiKeyVault.get(context);
                    String action = data.optString("action", "");
                    if ("add".equals(action)) {
                        if (!(data.opt("key") instanceof String)) return json(Response.Status.BAD_REQUEST, "Некорректный ключ");
                        try { vault.add(data.getString("key").trim()); }
                        catch (IllegalArgumentException e) { return json(Response.Status.BAD_REQUEST, e.getMessage()); }
                        AppLog.add("API: ключ добавлен через веб-интерфейс");
                    } else if ("delete".equals(action)) {
                        String id = data.optString("id", "");
                        if (!id.matches("[0-9a-f-]{36}") || !vault.remove(id))
                            return json(Response.Status.BAD_REQUEST, "Ключ не найден; обновите страницу");
                        AppLog.add("API: ключ удалён через веб-интерфейс");
                    } else return json(Response.Status.BAD_REQUEST, "Неизвестное действие");
                    return json(Response.Status.OK, "Сохранено");
                }
                if (session.getMethod() == Method.POST && ("/api/network".equals(path) || "/api/prompt".equals(path))) {
                    if (!WebSecurity.permittedWrite(host, session.getHeaders().get("origin"), session.getHeaders().get("x-gemini-station")))
                        return json(Response.Status.FORBIDDEN, "Запрос отклонён");
                    String lengthHeader = session.getHeaders().get("content-length");
                    long length = lengthHeader == null ? -1 : Long.parseLong(lengthHeader);
                    if (length < 1 || length > 50000 || session.getHeaders().containsKey("transfer-encoding"))
                        return json(Response.Status.BAD_REQUEST, "Недопустимый размер запроса");
                    Map<String, String> files = new HashMap<>(); session.parseBody(files);
                    JSONObject data = new JSONObject(files.get("postData"));
                    if ("/api/network".equals(path)) {
                        if (!(data.opt("enabled") instanceof Boolean) || !(data.opt("bypassVpn") instanceof Boolean)
                                || !(data.opt("server") instanceof String))
                            return json(Response.Status.BAD_REQUEST, "Некорректные настройки DNS");
                        String server = data.getString("server").trim();
                        if (!DnsSettings.validServer(server))
                            return json(Response.Status.BAD_REQUEST, "Введите IP-адрес DNS или HTTPS URL DNS-over-HTTPS");
                        if (!AppSettings.saveDns(context, data.getBoolean("enabled"), data.getBoolean("bypassVpn"), server))
                            return json(Response.Status.INTERNAL_ERROR, "Не удалось сохранить DNS");
                        AppLog.add("DNS: настройки сохранены через веб-интерфейс");
                    } else {
                        boolean reset = data.optBoolean("reset", false);
                        if (data.has("reset") && !(data.opt("reset") instanceof Boolean))
                            return json(Response.Status.BAD_REQUEST, "Некорректный запрос");
                        if (!reset && !(data.opt("prompt") instanceof String))
                            return json(Response.Status.BAD_REQUEST, "Введите текст промпта");
                        String prompt = reset ? SystemPrompt.DEFAULT : data.getString("prompt");
                        if (!SystemPrompt.valid(prompt)) return json(Response.Status.BAD_REQUEST, "Промпт слишком длинный (максимум 8000 символов)");
                        if (!AppSettings.saveSystemPrompt(context, prompt)) return json(Response.Status.INTERNAL_ERROR, "Не удалось сохранить промпт");
                        AppLog.add(reset ? "Системный промпт восстановлен" : "Системный промпт сохранён");
                    }
                    return json(Response.Status.OK, "Настройки сохранены");
                }
                if (session.getMethod() == Method.POST && "/api/config".equals(path)) {
                    if (!WebSecurity.permittedWrite(host, session.getHeaders().get("origin"), session.getHeaders().get("x-gemini-station")))
                        return json(Response.Status.FORBIDDEN, "Запрос отклонён");
                    // Require a small, known-length body before NanoHTTPD reads it.
                    String lengthHeader = session.getHeaders().get("content-length");
                    long length = lengthHeader == null ? -1 : Long.parseLong(lengthHeader);
                    if (length < 1 || length > 4096 || session.getHeaders().containsKey("transfer-encoding"))
                        return json(Response.Status.BAD_REQUEST, "Недопустимый размер запроса");
                    Map<String, String> files = new HashMap<>();
                    session.parseBody(files);
                    JSONObject data = new JSONObject(files.get("postData"));
                    String model = data.optString("model", "").trim();
                    // Older clients that omit voice retain the previously saved choice.
                    String voice = data.has("voice") ? data.optString("voice", "") : AppSettings.voice(context);
                    if (data.has("buttonEnabled") && !(data.opt("buttonEnabled") instanceof Boolean))
                        return json(Response.Status.BAD_REQUEST, "Некорректное состояние кнопки");
                    boolean buttonEnabled = data.has("buttonEnabled") ? data.optBoolean("buttonEnabled") : AppSettings.buttonEnabled(context);
                    if (data.has("wakeEnabled") && !(data.opt("wakeEnabled") instanceof Boolean))
                        return json(Response.Status.BAD_REQUEST, "Некорректное состояние голосовой активации");
                    boolean wakeEnabled = data.has("wakeEnabled") ? data.optBoolean("wakeEnabled") : AppSettings.wakeEnabled(context);
                    if (data.has("wakeSensitivity") && !(data.opt("wakeSensitivity") instanceof String))
                        return json(Response.Status.BAD_REQUEST, "Некорректная чувствительность");
                    String wakeSensitivity = data.has("wakeSensitivity") ? data.optString("wakeSensitivity", "") : AppSettings.wakeSensitivity(context);
                    if (!WakeSensitivity.valid(wakeSensitivity)) return json(Response.Status.BAD_REQUEST, "Некорректная чувствительность");
                    String key = data.optString("key", "").trim();
                    boolean deleteKey = data.optBoolean("deleteKey", false);
                    if (!AppSettings.validModel(model) || !TtsVoices.valid(voice) || key.length() > 512 || (deleteKey && !key.isEmpty()))
                        return json(Response.Status.BAD_REQUEST, "Некорректные настройки");
                    ApiKeyVault vault = ApiKeyVault.get(context);
                    if (deleteKey) vault.clear();
                    else if (!key.isEmpty()) {
                        try { vault.add(key); }
                        catch (IllegalArgumentException e) { return json(Response.Status.BAD_REQUEST, e.getMessage()); }
                    }
                    if (!AppSettings.save(context, model, voice, buttonEnabled, wakeEnabled, wakeSensitivity)) return json(Response.Status.INTERNAL_ERROR, "Не удалось сохранить настройки");
                    WakeWordController.get(context).settingsChanged();
                    AppLog.add(deleteKey ? "API-ключ удалён через веб-интерфейс" : "Настройки сохранены через веб-интерфейс");
                    return json(Response.Status.OK, "Настройки сохранены");
                }
                return json(Response.Status.NOT_FOUND, "Не найдено");
            } catch (Exception e) {
                // Do not expose raw exceptions; they may include request contents.
                AppLog.add("Веб-сервер: ошибка обработки запроса " + e.getClass().getSimpleName());
                return json(Response.Status.INTERNAL_ERROR, "Не удалось обработать запрос или сохранить настройки");
            }
        }
    }
}
