package ru.geministation;

import android.content.Context;
import android.media.AudioAttributes;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

/** Asynchronous, cancelable Android engine; no speech text or engine error bodies are logged. */
final class AndroidTtsPlayer {
    interface Listener { void started(); void done(); void error(String reason); }
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicInteger generation = new AtomicInteger();
    private TextToSpeech tts;
    private Runnable timeout;
    AndroidTtsPlayer(Context context) { this.context = context.getApplicationContext(); }
    void stop() {
        int stopped = generation.incrementAndGet();
        main.post(() -> { if (generation.get() == stopped) release(); });
    }
    private void release() {
        if (timeout != null) { main.removeCallbacks(timeout); timeout = null; }
        if (tts != null) {
            try { tts.stop(); } catch (RuntimeException ignored) { }
            try { tts.shutdown(); } catch (RuntimeException ignored) { }
            tts = null;
        }
    }
    void speak(String engine, String text, Listener listener) {
        int id = generation.incrementAndGet();
        main.post(() -> begin(id, engine, text, listener));
    }
    private void begin(int id, String engine, String text, Listener listener) {
        if (generation.get() != id) return;
        release();
        if (AndroidTtsEngines.GEMINI.equals(engine) || !AndroidTtsEngines.available(context, engine)) {
            fail(id, listener, "Выбранный Android TTS не установлен или недоступен"); return;
        }
        if (text == null || text.isEmpty() || text.length() > 16000) {
            fail(id, listener, "Слишком длинный или пустой текст для Android TTS"); return;
        }
        timeout = () -> fail(id, listener, "Android TTS не ответил вовремя");
        main.postDelayed(timeout, 12000);
        try {
            // OnInit may be synchronous: post it until the constructor has assigned tts.
            tts = new TextToSpeech(context, status -> main.post(() -> initialized(id, engine, text, listener, status)), engine);
        } catch (RuntimeException e) { fail(id, listener, "Не удалось открыть Android TTS"); }
    }
    private void initialized(int id, String engine, String text, Listener listener, int status) {
        try { initializedChecked(id, engine, text, listener, status); }
        catch (RuntimeException e) { fail(id, listener, "Ошибка выбранного Android TTS"); }
    }
    private void initializedChecked(int id, String engine, String text, Listener listener, int status) {
        if (generation.get() != id || tts == null) return;
        if (status != TextToSpeech.SUCCESS) { fail(id, listener, "Android TTS не инициализировался"); return; }
        // Android 9's public constructor may silently fall back to another engine. The
        // getter is light-greylisted on API 28; fail closed if it cannot be verified.
        try {
            Object current = TextToSpeech.class.getMethod("getCurrentEngine").invoke(tts);
            if (!engine.equals(current)) { fail(id, listener, "Выбранный TTS недоступен; автозамена отменена"); return; }
        } catch (Exception e) { fail(id, listener, "Не удалось проверить выбранный Android TTS"); return; }
        int language = tts.setLanguage(new Locale("ru", "RU"));
        if (language == TextToSpeech.LANG_MISSING_DATA || language == TextToSpeech.LANG_NOT_SUPPORTED)
            language = tts.setLanguage(new Locale("ru"));
        if (language == TextToSpeech.LANG_MISSING_DATA || language == TextToSpeech.LANG_NOT_SUPPORTED) {
            fail(id, listener, "В выбранном TTS нет русского языка или его голосовых данных"); return;
        }
        tts.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
        List<String> parts = TextChunks.split(text, Math.max(2, TextToSpeech.getMaxSpeechInputLength() - 1));
        final String last = id + ":" + (parts.size() - 1);
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String utteranceId) {
                if ((id + ":0").equals(utteranceId)) main.post(() -> { if (generation.get() == id) listener.started(); });
            }
            @Override public void onDone(String utteranceId) {
                if (last.equals(utteranceId)) main.post(() -> {
                    if (generation.compareAndSet(id, id + 1)) { release(); listener.done(); }
                });
            }
            @Override public void onError(String utteranceId) {
                if (utteranceId != null && utteranceId.startsWith(id + ":")) main.post(() -> fail(id, listener, "Ошибка выбранного Android TTS"));
            }
            @Override public void onError(String utteranceId, int errorCode) { onError(utteranceId); }
            @Override public void onStop(String utteranceId, boolean interrupted) {
                if (utteranceId != null && utteranceId.startsWith(id + ":")) main.post(() -> fail(id, listener, "Android TTS прервал озвучку"));
            }
        });
        if (timeout != null) main.removeCallbacks(timeout);
        timeout = () -> fail(id, listener, "Превышено время озвучки Android TTS");
        main.postDelayed(timeout, Math.min(180000L, 30000L + text.length() * 180L));
        for (int i = 0; i < parts.size(); i++) {
            int result = tts.speak(parts.get(i), i == 0 ? TextToSpeech.QUEUE_FLUSH : TextToSpeech.QUEUE_ADD,
                    new Bundle(), id + ":" + i);
            if (result != TextToSpeech.SUCCESS) { fail(id, listener, "Android TTS отклонил озвучку"); return; }
        }
    }
    private void fail(int id, Listener listener, String reason) {
        if (!generation.compareAndSet(id, id + 1)) return;
        release(); listener.error(reason);
    }
}
