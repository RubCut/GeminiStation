package ru.geministation;

import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;
import java.net.Proxy;
import java.util.concurrent.TimeUnit;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.dnsoverhttps.DnsOverHttps;

/** Gemini requests use DoH; the OS resolver is used only to bootstrap the DoH host. */
final class GeminiClient {
    private final OkHttpClient client;
    GeminiClient() {
        OkHttpClient bootstrap = new OkHttpClient.Builder().proxy(Proxy.NO_PROXY).connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build();
        DnsOverHttps dns = new DnsOverHttps.Builder().client(bootstrap)
                .url(HttpUrl.get("https://d.webshiftvpn.ru/dns-query"))
                .includeIPv6(false).post(true).build();
        client = bootstrap.newBuilder().dns(dns).readTimeout(90, TimeUnit.SECONDS).build();
    }
    String generate(String apiKey, String model, JSONArray history) throws Exception {
        if (!model.matches("[A-Za-z0-9._-]{1,80}")) throw new IOException("Неверное имя модели");
        JSONObject data = new JSONObject();
        data.put("contents", history);
        data.put("systemInstruction", new JSONObject().put("parts", new JSONArray().put(new JSONObject().put("text",
                "Ты голосовой помощник Gemini Station. Отвечай по-русски кратко и естественно, если пользователь не попросил иначе."))));
        Request request = new Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent")
                .header("x-goog-api-key", apiKey)
                .post(RequestBody.create(data.toString(), MediaType.get("application/json; charset=utf-8"))).build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) throw new IOException("Gemini API: HTTP " + response.code() +
                    ". Проверьте ключ, модель и доступность сервиса.");
            if (response.body() == null) throw new IOException("Пустой ответ Gemini");
            JSONObject json = new JSONObject(response.body().string());
            JSONArray candidates = json.optJSONArray("candidates");
            if (candidates == null || candidates.length() == 0) throw new IOException("Gemini не вернул ответ (возможно, запрос заблокирован фильтром).");
            JSONObject content = candidates.getJSONObject(0).optJSONObject("content");
            JSONArray parts = content == null ? null : content.optJSONArray("parts");
            StringBuilder text = new StringBuilder();
            if (parts != null) for (int i = 0; i < parts.length(); i++) {
                String part = parts.getJSONObject(i).optString("text", "");
                if (!part.isEmpty()) { if (text.length() > 0) text.append('\n'); text.append(part); }
            }
            if (text.length() == 0) throw new IOException("Gemini вернул ответ без текста.");
            return text.toString();
        }
    }
    /** Separate Gemini TTS model; the conversation model itself generates text only. */
    PcmAudio synthesize(String apiKey, String text) throws Exception {
        JSONObject voice = new JSONObject().put("voiceConfig", new JSONObject().put("voice", "Kore"));
        JSONObject config = new JSONObject().put("responseModalities", new JSONArray().put("AUDIO"))
                .put("speechConfig", voice);
        JSONObject data = new JSONObject().put("contents", new JSONArray().put(new JSONObject()
                .put("role", "user").put("parts", new JSONArray().put(new JSONObject().put("text", text)))))
                .put("generationConfig", config);
        Request request = new Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/models/gemini-3.8-flash-lite-tts:generateContent")
                .tag(String.class, "speech")
                .header("x-goog-api-key", apiKey)
                .post(RequestBody.create(data.toString(), MediaType.get("application/json; charset=utf-8"))).build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) throw new IOException("Gemini TTS: HTTP " + response.code());
            if (response.body() == null || response.body().contentLength() > 12 * 1024 * 1024)
                throw new IOException("Invalid TTS response size");
            // Bound even chunked responses before parsing base64 JSON.
            okio.BufferedSource source = response.body().source();
            source.request(12 * 1024 * 1024L + 1);
            if (source.buffer().size() > 12 * 1024 * 1024L) throw new IOException("TTS response too large");
            JSONObject json = new JSONObject(source.readUtf8());
            JSONArray candidates = json.optJSONArray("candidates");
            if (candidates == null || candidates.length() == 0) throw new IOException("Gemini TTS returned no audio");
            JSONObject content = candidates.getJSONObject(0).optJSONObject("content");
            JSONArray parts = content == null ? null : content.optJSONArray("parts");
            if (parts != null) for (int i = 0; i < parts.length(); i++) {
                JSONObject audio = parts.getJSONObject(i).optJSONObject("inlineData");
                if (audio == null) continue;
                String encoded = audio.optString("data", "");
                if (encoded.length() > 11 * 1024 * 1024) throw new IOException("TTS audio too large");
                return PcmAudio.decode(Base64.decode(encoded, Base64.DEFAULT), audio.optString("mimeType", ""));
            }
            throw new IOException("Gemini TTS returned no audio");
        }
    }
    void cancelSpeech() {
        for (okhttp3.Call call : client.dispatcher().queuedCalls()) if ("speech".equals(call.request().tag(String.class))) call.cancel();
        for (okhttp3.Call call : client.dispatcher().runningCalls()) if ("speech".equals(call.request().tag(String.class))) call.cancel();
    }
    void cancel() { client.dispatcher().cancelAll(); }
}
