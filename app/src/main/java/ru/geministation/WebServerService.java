package ru.geministation;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
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
        } catch (Exception e) {
            running = false;
            AppLog.add("Веб-сервер: ошибка запуска " + e.getClass().getSimpleName() + "; порт занят или недоступен");
            stopSelf();
        }
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) { return START_STICKY; }
    @Override public void onDestroy() {
        if (server != null) server.stop();
        running = false;
        AppLog.add("Веб-сервер остановлен");
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }

    private static final class Server extends NanoHTTPD {
        private final Context context;
        private final String page;
        Server(Context context) throws Exception {
            super(PORT); // Listen on all interfaces. Use ADB forwarding on untrusted networks.
            this.context = context;
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
            response.addHeader("Content-Security-Policy", "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; connect-src 'self'; base-uri 'none'; frame-ancestors 'none'; form-action 'none'");
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
            if (!path.startsWith("/api/")) return json(Response.Status.NOT_FOUND, "Не найдено");
            try {
                if (session.getMethod() == Method.GET && "/api/logs".equals(path)) {
                    JSONObject result = new JSONObject().put("logs", AppLog.snapshot()).put("model", AppSettings.model(context));
                    return reply(Response.Status.OK, "application/json; charset=utf-8", result.toString());
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
                    String key = data.optString("key", "").trim();
                    boolean deleteKey = data.optBoolean("deleteKey", false);
                    if (!AppSettings.validModel(model) || key.length() > 512 || (deleteKey && !key.isEmpty()))
                        return json(Response.Status.BAD_REQUEST, "Некорректные настройки");
                    SecretStore store = new SecretStore(context);
                    if (deleteKey) store.clear(); else if (!key.isEmpty()) store.save(key);
                    if (!AppSettings.saveModel(context, model)) return json(Response.Status.INTERNAL_ERROR, "Не удалось сохранить модель");
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
