package ru.geministation;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Base64;
import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import org.json.JSONArray;
import org.json.JSONObject;

/** One microphone turn streamed to Gemini Live. All UI callbacks are delivered on main. */
final class LiveTranscriber {
    interface Listener {
        void onReady();
        void onPartial(String value);
        void onFinal(String value);
        void onError(String message);
    }
    private static final int RATE = 16000;
    private static final int CHUNK = 3200; // 100 ms, signed 16-bit mono
    private final GeminiClient client;
    private final String key;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Object lock = new Object();
    private final AtomicBoolean stopped = new AtomicBoolean();
    private final AtomicBoolean ending = new AtomicBoolean();
    private final AtomicBoolean captureStarted = new AtomicBoolean();
    private volatile WebSocket socket;
    private AudioRecord recording;
    private long started;

    LiveTranscriber(GeminiClient client, String key, Listener listener) {
        this.client = client; this.key = key; this.listener = listener;
    }
    void start() {
        started = SystemClock.elapsedRealtime();
        AppLog.add("Live STT: подключаем Gemini Live через WebShift DNS");
        socket = client.openTranscriptionSocket(key, new WebSocketListener() {
            @Override public void onOpen(WebSocket webSocket, Response response) {
                if (stopped.get()) { webSocket.cancel(); return; }
                socket = webSocket;
                try {
                    JSONObject setup = new JSONObject().put("model", "models/gemini-3.5-transcribe-live")
                            .put("generationConfig", new JSONObject().put("responseModalities", new JSONArray().put("TEXT")))
                            .put("inputAudioTranscription", new JSONObject().put("languageCodes", new JSONArray().put("ru-RU")));
                    if (!webSocket.send(new JSONObject().put("setup", setup).toString())) throw new IOException("closed");
                    AppLog.add("Live STT: WebSocket подключен за " + (SystemClock.elapsedRealtime() - started) + " мс");
                } catch (Exception e) { fail("Не удалось подготовить Gemini Live", e.getClass().getSimpleName()); }
            }
            @Override public void onMessage(WebSocket webSocket, String message) {
                if (stopped.get()) return;
                try {
                    JSONObject data = new JSONObject(message);
                    if (data.has("error")) {
                        JSONObject error = data.optJSONObject("error");
                        int code = error == null ? 0 : error.optInt("code", 0);
                        fail("Gemini Live отклонил запрос" + (code == 0 ? "" : " (" + code + ")"), "API error " + code);
                        return;
                    }
                    if (data.has("setupComplete") && captureStarted.compareAndSet(false, true)) {
                        AppLog.add("Live STT: сессия готова за " + (SystemClock.elapsedRealtime() - started) + " мс");
                        new Thread(() -> capture(webSocket), "GeminiMicrophone").start();
                    }
                    JSONObject content = data.optJSONObject("serverContent");
                    if (content == null) return;
                    JSONObject partial = content.optJSONObject("interimInputTranscription");
                    if (partial != null) {
                        String value = partial.optString("text", "");
                        if (!value.trim().isEmpty()) main.post(() -> { if (!stopped.get()) listener.onPartial(value); });
                    }
                    JSONObject result = content.optJSONObject("inputTranscription");
                    if (result != null) {
                        String value = result.optString("text", "").trim();
                        if (!value.isEmpty() && stopped.compareAndSet(false, true)) {
                            AppLog.add("Live STT: итог получен за " + (SystemClock.elapsedRealtime() - started) + " мс; символов " + value.length());
                            releaseResources();
                            main.post(() -> listener.onFinal(value));
                        }
                    }
                } catch (Exception e) { fail("Не удалось обработать ответ Gemini Live", e.getClass().getSimpleName()); }
            }
            @Override public void onFailure(WebSocket webSocket, Throwable error, Response response) {
                int code = response == null ? 0 : response.code();
                String detail = error.getClass().getSimpleName() + (code == 0 ? "" : ", HTTP " + code);
                fail("Ошибка Gemini Live" + (code == 0 ? "" : " (HTTP " + code + ")"), detail);
            }
            @Override public void onClosing(WebSocket webSocket, int code, String reason) {
                fail("Gemini Live завершил соединение (" + code + ")", "WebSocket code " + code);
            }
            @Override public void onClosed(WebSocket webSocket, int code, String reason) {
                fail("Gemini Live закрыл соединение (" + code + ")", "WebSocket code " + code);
            }
        });
    }
    private AudioRecord createRecorder(int source, int size) {
        try {
            AudioRecord recorder = new AudioRecord(source, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, size);
            if (recorder.getState() == AudioRecord.STATE_INITIALIZED) return recorder;
            recorder.release(); return null;
        } catch (IllegalArgumentException e) {
            AppLog.add("Live STT: источник микрофона не поддерживается");
            return null;
        }
    }
    private void capture(WebSocket webSocket) {
        AudioRecord recorder = null;
        try {
            int minimum = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (minimum <= 0) throw new IOException("16 kHz audio not supported");
            synchronized (lock) {
                if (stopped.get() || ending.get()) return;
                recorder = createRecorder(MediaRecorder.AudioSource.MIC, Math.max(minimum, CHUNK * 4));
                if (recorder == null) {
                    AppLog.add("Live STT: MIC недоступен, пробуем VOICE_RECOGNITION");
                    recorder = createRecorder(MediaRecorder.AudioSource.VOICE_RECOGNITION, Math.max(minimum, CHUNK * 4));
                }
                if (recorder == null) throw new IOException("AudioRecord initialization failed");
                recording = recorder;
                recorder.startRecording();
                if (recorder.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) throw new IOException("AudioRecord start failed");
            }
            AppLog.add("Live STT: запись микрофона запущена (16 кГц, mono)");
            main.post(() -> { if (!stopped.get()) listener.onReady(); });
            byte[] buffer = new byte[CHUNK];
            long lastLevel = SystemClock.elapsedRealtime(), samples = 0, sumSquares = 0;
            int peak = 0;
            while (!stopped.get() && !ending.get()) {
                int count = recorder.read(buffer, 0, buffer.length);
                if (count < 0) {
                    if (!stopped.get() && !ending.get()) {
                        AppLog.add("Live STT: AudioRecord.read вернул код " + count);
                        throw new IOException("AudioRecord read failed");
                    }
                    break;
                }
                if (count == 0) continue;
                if (stopped.get() || ending.get()) break;
                count -= count % 2;
                if (count == 0) continue;
                for (int i = 0; i < count; i += 2) {
                    int sample = (short) ((buffer[i] & 0xff) | (buffer[i + 1] << 8));
                    peak = Math.max(peak, Math.abs(sample));
                    sumSquares += (long) sample * sample; samples++;
                }
                long now = SystemClock.elapsedRealtime();
                if (now - lastLevel >= 2000 && samples > 0) {
                    int rms = (int) Math.sqrt((double) sumSquares / samples);
                    AppLog.add("Live STT: уровень микрофона RMS=" + rms + " пик=" + peak + " (0–32768)");
                    peak = 0; samples = 0; sumSquares = 0; lastLevel = now;
                }
                JSONObject audio = new JSONObject().put("mimeType", "audio/pcm;rate=16000")
                        .put("data", Base64.encodeToString(Arrays.copyOf(buffer, count), Base64.NO_WRAP));
                if (webSocket.queueSize() > 512 * 1024 || !webSocket.send(new JSONObject().put("realtimeInput", new JSONObject().put("audio", audio)).toString()))
                    throw new IOException("WebSocket audio queue unavailable");
            }
        } catch (Exception e) {
            if (!stopped.get() && !ending.get()) fail("Не удалось получить звук с микрофона", e.getClass().getSimpleName());
        } finally {
            synchronized (lock) {
                if (recorder != null) {
                    try { recorder.stop(); } catch (IllegalStateException ignored) { }
                    recorder.release();
                    if (recording == recorder) recording = null;
                }
            }
            AppLog.add("Live STT: запись микрофона остановлена");
        }
    }
    void finishInput() {
        if (stopped.get() || !ending.compareAndSet(false, true)) return;
        AppLog.add("Live STT: вручную завершён ввод; ждём итог");
        stopRecording();
        try {
            WebSocket current = socket;
            if (current == null || !current.send(new JSONObject().put("realtimeInput", new JSONObject().put("audioStreamEnd", true)).toString()))
                fail("Не удалось завершить аудиопоток Gemini", "WebSocket unavailable");
        } catch (Exception e) { fail("Не удалось завершить аудиопоток Gemini", e.getClass().getSimpleName()); }
    }
    void cancel() {
        if (!stopped.compareAndSet(false, true)) return;
        AppLog.add("Live STT: сессия закрыта");
        releaseResources();
    }
    private void fail(String message, String detail) {
        if (!stopped.compareAndSet(false, true)) return;
        AppLog.add("Live STT: " + detail);
        releaseResources();
        main.post(() -> listener.onError(message));
    }
    private void stopRecording() {
        synchronized (lock) {
            if (recording != null) try { recording.stop(); } catch (IllegalStateException ignored) { }
        }
    }
    private void releaseResources() {
        stopRecording();
        WebSocket current = socket;
        if (current != null) current.cancel();
    }
}
