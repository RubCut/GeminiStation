package ru.geministation;

import android.util.Base64;
import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.net.Proxy;
import java.util.concurrent.TimeUnit;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.dnsoverhttps.DnsOverHttps;

/** Network settings apply to new requests; running audio streams are not interrupted. */
final class GeminiClient {
    private final Context context;
    private final OkHttpClient bootstrap;
    private OkHttpClient client;
    private String clientKey;
    GeminiClient(Context context) {
        this.context = context.getApplicationContext();
        bootstrap = new OkHttpClient.Builder().proxy(Proxy.NO_PROXY).connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false)
                .eventListenerFactory(call -> new NetworkDiagnostics("DoH", null)).build();
    }
    private synchronized OkHttpClient client() throws IOException {
        boolean enabled = AppSettings.dnsEnabled(context);
        boolean bypass = AppSettings.dnsBypassVpn(context);
        boolean vpn = bypass && VpnStatus.active(context);
        DnsSettings.Mode mode = DnsSettings.mode(enabled, bypass, vpn);
        String server = AppSettings.dnsServer(context);
        String key = mode == DnsSettings.Mode.SYSTEM ? "system" : "custom:" + server;
        if (client != null && key.equals(clientKey)) return client;
        okhttp3.Dns resolver = okhttp3.Dns.SYSTEM;
        TimedDns cached = null;
        if (mode == DnsSettings.Mode.CUSTOM) {
            if (!DnsSettings.validServer(server)) throw new IOException("Некорректный DNS-сервер");
            okhttp3.Dns upstream;
            if (DnsSettings.isIp(server)) upstream = new PlainDns(server);
            else upstream = new DnsOverHttps.Builder().client(bootstrap).url(HttpUrl.get(server))
                    .includeIPv6(false).post(true).build();
            cached = new TimedDns(upstream, System::nanoTime, () -> AppLog.add("DNS: ответ из кэша (до 60 с)"));
            resolver = cached;
        }
        final TimedDns dnsCache = cached;
        // Drop idle connections that could otherwise outlive a network/DNS policy change.
        bootstrap.connectionPool().evictAll();
        client = bootstrap.newBuilder().dns(resolver).readTimeout(90, TimeUnit.SECONDS)
                .eventListenerFactory(call -> new NetworkDiagnostics(
                        "speech".equals(call.request().tag(String.class)) ? "Gemini TTS" :
                                "transcribe".equals(call.request().tag(String.class)) ? "Gemini Live" :
                                        "youtube".equals(call.request().tag(String.class)) ? "YouTube поиск" : "Gemini текст",
                        dnsCache == null ? null : dnsCache::clear)).build();
        clientKey = key;
        AppLog.add(mode == DnsSettings.Mode.CUSTOM ? "DNS: пользовательский сервер" :
                (enabled && vpn ? "DNS: системный (VPN активен)" : "DNS: системный"));
        return client;
    }
    GeminiToolProtocol.Result generate(String apiKey, String model, JSONArray history, JSONArray apps) throws Exception {
        if (!model.matches("[A-Za-z0-9._-]{1,80}")) throw new IOException("Неверное имя модели");
        long started = System.nanoTime();
        AppLog.add("Текст: подготовка запроса; реплик в истории " + history.length());
        JSONObject data = GeminiToolProtocol.request(history, apps, AppSettings.systemPrompt(context));
        Request request = new Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent")
                .header("x-goog-api-key", apiKey)
                .post(RequestBody.create(data.toString(), MediaType.get("application/json; charset=utf-8"))).build();
        try (Response response = client().newCall(request).execute()) {
            if (RateLimitSignals.httpResponse(response)) throw new KeyRotation.RateLimit("Gemini API");
            if (!response.isSuccessful()) throw new IOException("Gemini API: HTTP " + response.code() +
                    ". Проверьте ключ, модель и доступность сервиса.");
            if (response.body() == null) throw new IOException("Пустой ответ Gemini");
            JSONObject payload = new JSONObject(response.body().string());
            if (RateLimitSignals.apiPayload(payload)) throw new KeyRotation.RateLimit("Gemini API");
            GeminiToolProtocol.Result result = GeminiToolProtocol.parse(payload);
            AppLog.add("Текст: ответ обработан за " + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
                    + (result.call == null ? " мс; получен текст" : " мс; получена команда"));
            return result;
        }
    }
    /** Search only real YouTube Data API results; never invent video identifiers. */
    String searchYoutube(String apiKey, String query) throws Exception {
        if (!LinkPolicy.searchQuery(query)) throw new IOException("Некорректный поисковый запрос");
        HttpUrl url = new HttpUrl.Builder().scheme("https").host("www.googleapis.com")
                .addPathSegment("youtube").addPathSegment("v3").addPathSegment("search")
                .addQueryParameter("part", "snippet").addQueryParameter("type", "video")
                .addQueryParameter("maxResults", "1").addQueryParameter("q", query).build();
        Request request = new Request.Builder().url(url).tag(String.class, "youtube")
                .header("x-goog-api-key", apiKey).get().build();
        try (Response response = client().newBuilder().callTimeout(12, TimeUnit.SECONDS).build().newCall(request).execute()) {
            if (RateLimitSignals.httpResponse(response)) throw new KeyRotation.RateLimit("YouTube API");
            if (!response.isSuccessful()) throw new IOException("YouTube API: HTTP " + response.code());
            if (response.body() == null) throw new IOException("Пустой ответ поиска YouTube");
            okio.BufferedSource source = response.body().source();
            source.request(1024 * 1024L + 1);
            if (source.buffer().size() > 1024 * 1024L) throw new IOException("YouTube response too large");
            return YoutubeSearchProtocol.videoUrl(new JSONObject(source.readUtf8()));
        }
    }
    /** Both streaming and non-streaming requests use exactly the same chosen voice. */
    static JSONObject speechRequest(String text, String voiceName) throws Exception {
        if (!TtsVoices.valid(voiceName)) throw new IllegalArgumentException("Unknown TTS voice");
        JSONObject voice = new JSONObject().put("voiceConfig", new JSONObject().put("voice", voiceName));
        JSONObject config = new JSONObject().put("responseModalities", new JSONArray().put("AUDIO"))
                .put("speechConfig", voice);
        return new JSONObject().put("contents", new JSONArray().put(new JSONObject()
                .put("role", "user").put("parts", new JSONArray().put(new JSONObject().put("text", text)))))
                .put("generationConfig", config);
    }
    /** Separate Gemini TTS model; the conversation model itself generates text only. */
    PcmAudio synthesize(String apiKey, String text, String voiceName) throws Exception {
        long started = System.nanoTime();
        AppLog.add("TTS: подготовка запроса; символов " + text.length());
        JSONObject data = speechRequest(text, voiceName);
        Request request = new Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/models/gemini-3.8-flash-lite-tts:generateContent")
                .tag(String.class, "speech")
                .header("x-goog-api-key", apiKey)
                .post(RequestBody.create(data.toString(), MediaType.get("application/json; charset=utf-8"))).build();
        try (Response response = client().newCall(request).execute()) {
            if (RateLimitSignals.httpResponse(response)) throw new KeyRotation.RateLimit("Gemini TTS");
            if (!response.isSuccessful()) throw new IOException("Gemini TTS: HTTP " + response.code());
            if (response.body() == null || response.body().contentLength() > 12 * 1024 * 1024)
                throw new IOException("Invalid TTS response size");
            // Bound even chunked responses before parsing base64 JSON.
            okio.BufferedSource source = response.body().source();
            source.request(12 * 1024 * 1024L + 1);
            if (source.buffer().size() > 12 * 1024 * 1024L) throw new IOException("TTS response too large");
            JSONObject json = new JSONObject(source.readUtf8());
            if (RateLimitSignals.apiPayload(json)) throw new KeyRotation.RateLimit("Gemini TTS");
            JSONArray candidates = json.optJSONArray("candidates");
            if (candidates == null || candidates.length() == 0) throw new IOException("Gemini TTS returned no audio");
            JSONObject content = candidates.getJSONObject(0).optJSONObject("content");
            JSONArray parts = content == null ? null : content.optJSONArray("parts");
            if (parts != null) for (int i = 0; i < parts.length(); i++) {
                JSONObject audio = parts.getJSONObject(i).optJSONObject("inlineData");
                if (audio == null) continue;
                String encoded = audio.optString("data", "");
                if (encoded.length() > 11 * 1024 * 1024) throw new IOException("TTS audio too large");
                PcmAudio pcm = PcmAudio.decode(Base64.decode(encoded, Base64.DEFAULT), audio.optString("mimeType", ""));
                AppLog.add("TTS: аудио декодировано за " + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
                        + " мс; байт " + pcm.samples.length + ", частота " + pcm.rate + ", каналов " + pcm.channels);
                return pcm;
            }
            throw new IOException("Gemini TTS returned no audio");
        }
    }
    interface AudioConsumer { void accept(PcmAudio audio) throws Exception; }
    static final class StreamingUnavailableException extends IOException {
        StreamingUnavailableException(int status) { super("Gemini TTS streaming: HTTP " + status); }
    }
    /** Starts playback from the first PCM chunk, without waiting for the full TTS response. */
    void synthesizeStreaming(String apiKey, String text, String voiceName, AudioConsumer consumer) throws Exception {
        long started = System.nanoTime();
        JSONObject data = speechRequest(text, voiceName);
        Request request = new Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/models/gemini-3.8-flash-lite-tts:streamGenerateContent?alt=sse")
                .tag(String.class, "speech").header("x-goog-api-key", apiKey)
                .header("Accept", "text/event-stream")
                .post(RequestBody.create(data.toString(), MediaType.get("application/json; charset=utf-8"))).build();
        AppLog.add("TTS: потоковый запрос запущен; символов " + text.length());
        long total = 0; int chunks = 0;
        try (Response response = client().newCall(request).execute()) {
            if (RateLimitSignals.httpResponse(response)) throw new KeyRotation.RateLimit("Gemini TTS");
            if (response.code() == 404 || response.code() == 405 || response.code() == 501)
                throw new StreamingUnavailableException(response.code());
            if (!response.isSuccessful()) throw new IOException("Gemini TTS: HTTP " + response.code());
            String contentType = response.header("Content-Type", "").toLowerCase(java.util.Locale.ROOT);
            if (!contentType.startsWith("text/event-stream")) throw new StreamingUnavailableException(response.code());
            if (response.body() == null) throw new IOException("Empty TTS stream");
            try (BufferedReader input = new BufferedReader(new InputStreamReader(response.body().byteStream(), StandardCharsets.UTF_8))) {
                SseReader events = new SseReader(input);
                for (String event; (event = events.next()) != null;) {
                    if ("[DONE]".equals(event)) break;
                    JSONObject json = new JSONObject(event);
                    if (json.has("error")) {
                        JSONObject error = json.optJSONObject("error");
                        boolean limited = error != null && RateLimitSignals.apiError(error.optInt("code"), error.optString("status"));
                        // Do not replay from the beginning after any audio has reached the speaker.
                        if (limited) throw new KeyRotation.RateLimit("Gemini TTS", chunks == 0);
                        throw new IOException("TTS stream error");
                    }
                    JSONArray candidates = json.optJSONArray("candidates");
                    if (candidates == null || candidates.length() == 0) continue;
                    JSONObject content = candidates.getJSONObject(0).optJSONObject("content");
                    JSONArray parts = content == null ? null : content.optJSONArray("parts");
                    if (parts == null) continue;
                    for (int i = 0; i < parts.length(); i++) {
                        JSONObject audio = parts.getJSONObject(i).optJSONObject("inlineData");
                        if (audio == null) continue;
                        String encoded = audio.optString("data", "");
                        if (encoded.isEmpty()) continue;
                        if (encoded.length() > 1024 * 1024) throw new IOException("TTS chunk too large");
                        PcmAudio pcm = PcmAudio.decode(Base64.decode(encoded, Base64.DEFAULT), audio.optString("mimeType", ""));
                        total += pcm.samples.length;
                        if (total > 8 * 1024 * 1024) throw new IOException("TTS stream too large");
                        if (++chunks == 1) AppLog.add("TTS: первый аудиофрагмент через " + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) + " мс");
                        consumer.accept(pcm);
                    }
                }
            }
        }
        if (chunks == 0) throw new IOException("TTS stream returned no audio");
        AppLog.add("TTS: поток получен; фрагментов " + chunks + ", байт " + total);
    }
    okhttp3.WebSocket openTranscriptionSocket(String apiKey, okhttp3.WebSocketListener listener) throws IOException {
        Request request = new Request.Builder()
                .url("wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent")
                .tag(String.class, "transcribe").header("x-goog-api-key", apiKey).build();
        return client().newWebSocket(request, listener);
    }
    void cancelSpeech() {
        for (okhttp3.Call call : bootstrap.dispatcher().queuedCalls()) if ("speech".equals(call.request().tag(String.class))) call.cancel();
        for (okhttp3.Call call : bootstrap.dispatcher().runningCalls()) if ("speech".equals(call.request().tag(String.class))) call.cancel();
    }
    void cancel() { bootstrap.dispatcher().cancelAll(); }
}
