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
import org.json.JSONArray;
import org.json.JSONObject;

/** One microphone turn streamed to Gemini Live. All UI callbacks are delivered on main. */
final class LiveTranscriber {
    interface Listener {
        void onReady();
        void onConnected();
        void onPartial(String value);
        void onFinal(String value);
        void onError(String message, boolean rateLimited, boolean retrySafe);
    }
    private static final int RATE = 16000;
    private static final int CHUNK = 3200; // 100 ms, signed 16-bit mono
    private final GeminiClient client;
    private final String key;
    private final Listener listener;
    private final VoiceTuning tuning;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Object lock = new Object();
    private static final int MAX_PENDING_BYTES = 16000 * 2 * 10;
    private final AudioStreamBuffer pendingAudio = new AudioStreamBuffer(MAX_PENDING_BYTES);
    private final AtomicBoolean stopped = new AtomicBoolean();
    private final AtomicBoolean ending = new AtomicBoolean();
    private final AtomicBoolean captureStarted = new AtomicBoolean();
    private final AtomicBoolean setupReady = new AtomicBoolean(), captureAllowed = new AtomicBoolean();
    private final AtomicBoolean binaryFrameLogged = new AtomicBoolean();
    private final TranscriptAccumulator transcript = new TranscriptAccumulator();
    private volatile long lastSignalAt;
    private Runnable transcriptTimer;
    private volatile WebSocket socket;
    private AudioRecord recording;
    private long started;

    LiveTranscriber(GeminiClient client, String key, VoiceTuning tuning, Listener listener) {
        this.client = client; this.key = key; this.tuning = tuning; this.listener = listener;
    }
    void start() {
        started = SystemClock.elapsedRealtime();
        AppLog.add("Live STT: подключаем Gemini Live");
        try { socket = client.openTranscriptionSocket(key, new GeminiSocketListener() {
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
            @Override protected void onBinaryFrame() {
                if (binaryFrameLogged.compareAndSet(false, true)) AppLog.add("Live STT: получен бинарный кадр Gemini");
            }
            @Override protected void onInvalidFrame(String reason) {
                fail("Некорректный ответ Gemini Live", reason);
            }
            @Override protected void onJsonFrame(WebSocket webSocket, String message) {
                if (stopped.get()) return;
                try {
                    JSONObject data = new JSONObject(message);
                    if (data.has("error")) {
                        JSONObject error = data.optJSONObject("error");
                        int code = error == null ? 0 : error.optInt("code", 0);
                        boolean limited = error != null && RateLimitSignals.apiError(code, error.optString("status"));
                        fail("Gemini Live отклонил запрос" + (limited ? " (429)" : (code == 0 ? "" : " (" + code + ")")), "API error " + code, limited);
                        return;
                    }
                    if (data.has("setupComplete") && setupReady.compareAndSet(false, true)) {
                        AppLog.add("Live STT: сессия готова за " + (SystemClock.elapsedRealtime() - started) + " мс");
                        int buffered = pendingAudio.connect(audioSink(webSocket));
                        if (buffered > 0) AppLog.add("Live STT: отправлен буфер до подключения " + buffered / 32 + " мс");
                        main.post(() -> { if (!stopped.get()) listener.onConnected(); });
                        maybeStartCapture();
                    }
                    JSONObject content = data.optJSONObject("serverContent");
                    if (content == null) return;
                    JSONObject partial = content.optJSONObject("interimInputTranscription");
                    if (partial != null) {
                        String value = partial.optString("text", "");
                        if (!value.trim().isEmpty()) {
                            String display = transcript.partial(value);
                            main.post(() -> {
                                if (!stopped.get()) { listener.onPartial(display); scheduleTranscriptCompletion(tuning.interimMs); }
                            });
                        }
                    }
                    JSONObject result = content.optJSONObject("inputTranscription");
                    if (result != null) {
                        String value = result.optString("text", "").trim();
                        if (!value.isEmpty()) {
                            String display = transcript.addFinal(value);
                            main.post(() -> {
                                if (!stopped.get()) { listener.onPartial(display); scheduleTranscriptCompletion(tuning.finalMs); }
                            });
                        }
                    }
                    if (content.optBoolean("turnComplete", false) && !transcript.best().isEmpty())
                        main.post(() -> completeTranscript(!transcript.hasFinal()));
                } catch (Exception e) { fail("Не удалось обработать ответ Gemini Live", e.getClass().getSimpleName()); }
            }
            @Override public void onFailure(WebSocket webSocket, Throwable error, Response response) {
                int code = response == null ? 0 : response.code();
                String detail = error.getClass().getSimpleName() + (code == 0 ? "" : ", HTTP " + code);
                fail("Ошибка Gemini Live" + (code == 0 ? "" : " (HTTP " + code + ")"), detail, RateLimitSignals.httpResponse(response));
            }
            @Override public void onClosing(WebSocket webSocket, int code, String reason) {
                fail("Gemini Live завершил соединение (" + code + ")", "WebSocket code " + code, RateLimitSignals.closeReason(reason));
            }
            @Override public void onClosed(WebSocket webSocket, int code, String reason) {
                fail("Gemini Live закрыл соединение (" + code + ")", "WebSocket code " + code, RateLimitSignals.closeReason(reason));
            }
        }); } catch (IOException e) { fail("Не удалось подключить Gemini Live. Проверьте настройки DNS", e.getClass().getSimpleName()); }
    }
    /** Connection may be prepared during the cue, but the microphone is never opened before it ends. */
    void allowCapture() { captureAllowed.set(true); maybeStartCapture(); }
    private void maybeStartCapture() {
        WebSocket current = socket;
        if (!stopped.get() && !ending.get() && (tuning.earlyCapture || setupReady.get()) && captureAllowed.get() && current != null
                && captureStarted.compareAndSet(false, true))
            new Thread(() -> capture(current), "GeminiMicrophone").start();
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
            AdaptiveSignal signal = new AdaptiveSignal();
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
                long frameSquares = 0;
                for (int i = 0; i < count; i += 2) {
                    int sample = (short) ((buffer[i] & 0xff) | (buffer[i + 1] << 8));
                    peak = Math.max(peak, Math.abs(sample));
                    long square = (long) sample * sample;
                    frameSquares += square; sumSquares += square; samples++;
                }
                long now = SystemClock.elapsedRealtime();
                int frameRms = (int) Math.sqrt((double) frameSquares / (count / 2));
                if (signal.accept(frameRms)) lastSignalAt = now;
                if (now - lastLevel >= 2000 && samples > 0) {
                    int rms = (int) Math.sqrt((double) sumSquares / samples);
                    AppLog.add("Live STT: уровень микрофона RMS=" + rms + " пик=" + peak + " (0–32768)");
                    peak = 0; samples = 0; sumSquares = 0; lastLevel = now;
                }
                byte[] chunk = Arrays.copyOf(buffer, count);
                if (ending.get() || stopped.get()) break;
                if (!pendingAudio.write(chunk, audioSink(webSocket))) {
                    if (ending.get() || stopped.get()) break;
                    throw new IOException("Gemini Live не подключился: буфер аудио 10 с заполнен");
                }
            }
        } catch (Exception e) {
            if (!stopped.get() && !ending.get()) {
                String detail = e.getMessage();
                boolean bufferFull = detail != null && detail.contains("буфер аудио");
                fail(bufferFull ? "Gemini Live не подключился вовремя" : "Не удалось получить звук с микрофона",
                        e.getClass().getSimpleName() + (bufferFull ? ": " + detail : ""));
            }
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
    private static AudioStreamBuffer.Sink audioSink(WebSocket webSocket) {
        return new AudioStreamBuffer.Sink() {
            public void audio(byte[] chunk) throws Exception {
                JSONObject audio = new JSONObject().put("mimeType", "audio/pcm;rate=16000")
                        .put("data", Base64.encodeToString(chunk, Base64.NO_WRAP));
                if (webSocket.queueSize() > 512 * 1024 || !webSocket.send(new JSONObject().put("realtimeInput", new JSONObject().put("audio", audio)).toString()))
                    throw new IOException("WebSocket audio queue unavailable");
            }
            public void end() throws Exception {
                if (!webSocket.send(new JSONObject().put("realtimeInput", new JSONObject().put("audioStreamEnd", true)).toString()))
                    throw new IOException("WebSocket unavailable");
            }
        };
    }
    private void scheduleTranscriptCompletion(long delay) {
        if (transcriptTimer != null) main.removeCallbacks(transcriptTimer);
        final long start = SystemClock.elapsedRealtime();
        transcriptTimer = new Runnable() {
            @Override public void run() {
                if (stopped.get()) return;
                long now = SystemClock.elapsedRealtime();
                // Avoid cutting the user off if they are still speaking. A noisy room cannot block forever.
                if (!ending.get() && now - lastSignalAt < 700 && now - start < 4500) {
                    main.postDelayed(this, 250); return;
                }
                completeTranscript(!transcript.hasFinal());
            }
        };
        main.postDelayed(transcriptTimer, delay);
    }
    private void completeTranscript(boolean interimOnly) {
        String value = transcript.best();
        if (value.isEmpty() || !stopped.compareAndSet(false, true)) return;
        if (transcriptTimer != null) main.removeCallbacks(transcriptTimer);
        long last = lastSignalAt;
        AppLog.add("Live STT: последний уровень выше фонового порога " + (last == 0 ? "не обнаружен" : (SystemClock.elapsedRealtime() - last) + " мс назад"));
        AppLog.add("Live STT: итог через " + (SystemClock.elapsedRealtime() - started) + " мс; символов " + value.length()
                + (interimOnly ? "; использован промежуточный результат" : ""));
        releaseResources();
        listener.onFinal(value);
    }
    boolean hasRecentSignal(long windowMs) {
        long at = lastSignalAt;
        return at > 0 && SystemClock.elapsedRealtime() - at < windowMs;
    }
    void finishInput() {
        if (stopped.get() || !ending.compareAndSet(false, true)) return;
        AppLog.add("Live STT: вручную завершён ввод; ждём итог");
        stopRecording();
        try {
            WebSocket current = socket;
            if (current == null) fail("Не удалось завершить аудиопоток Gemini", "WebSocket unavailable");
            else pendingAudio.finish(audioSink(current)); // Setup callback drains before end if still connecting.
        } catch (Exception e) { fail("Не удалось завершить аудиопоток Gemini", e.getClass().getSimpleName()); }
    }
    void cancel() {
        if (!stopped.compareAndSet(false, true)) return;
        AppLog.add("Live STT: сессия закрыта");
        if (transcriptTimer != null) main.removeCallbacks(transcriptTimer);
        releaseResources();
    }
    private void fail(String message, String detail) { fail(message, detail, false); }
    private void fail(String message, String detail, boolean rateLimited) {
        if (!stopped.compareAndSet(false, true)) return;
        boolean retrySafe = !captureStarted.get();
        AppLog.add("Live STT: " + detail);
        if (transcriptTimer != null) main.removeCallbacks(transcriptTimer);
        releaseResources();
        main.post(() -> listener.onError(message, rateLimited, retrySafe));
    }
    private void stopRecording() {
        synchronized (lock) {
            if (recording != null) try { recording.stop(); } catch (IllegalStateException ignored) { }
        }
    }
    private void releaseResources() {
        pendingAudio.cancel();
        stopRecording();
        WebSocket current = socket;
        if (current != null) current.cancel();
    }
}
