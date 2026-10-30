package ru.geministation;

import android.Manifest;
import android.animation.ObjectAnimator;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.LinearInterpolator;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public final class MainActivity extends Activity {
    private static final int MICROPHONE_REQUEST = 100;
    private static final long FOLLOW_UP_SILENCE_MS = 12000;
    private static final long INITIAL_SILENCE_MS = 18000;
    private static final long FINAL_RESULT_TIMEOUT_MS = 15000;
    private static final long MAX_UTTERANCE_MS = 45000;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final ExecutorService speechExecutor = Executors.newSingleThreadExecutor();
    private final AtomicInteger speechGeneration = new AtomicInteger();
    private final VoicePlayer voicePlayer = new VoicePlayer();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final JSONArray history = new JSONArray();
    private final ConversationFlow flow = new ConversationFlow();
    private GeminiClient client;
    private SecretStore secrets;
    private SpeechRecognizer recognizer;
    private LiveTranscriber liveTranscriber;
    private TextView text;
    private ImageView logo;
    private ObjectAnimator spin;
    private Runnable recognitionTimeout;
    private long recognitionStarted, speechEnded;
    private int recognitionAttempt, partialCount;
    private boolean waitingForFinal, heardSpeech, listening, destroyed, resumed;
    private boolean initialStartPending = true, permissionPending;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        secrets = new SecretStore(this);
        client = new GeminiClient();
        buildUi();
        try { startForegroundService(new Intent(this, WebServerService.class)); }
        catch (RuntimeException e) { AppLog.add("Веб-сервер: ошибка запуска " + e.getClass().getSimpleName()); }
        AppLog.add("Диалог: ассистент открыт");
    }
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (!Intent.ACTION_ASSIST.equals(intent.getAction())) return;
        if (listening) finishVoiceInput();
        else if (flow.phase() == ConversationFlow.Phase.SPEAKING) {
            stopSpeaking();
            beginFollowUp();
        }
    }
    private int dp(int value) { return (int) (value * getResources().getDisplayMetrics().density + .5f); }
    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setGravity(Gravity.BOTTOM | Gravity.START);
        root.setPadding(dp(24), dp(8), dp(24), dp(24));
        root.setBackgroundColor(Color.TRANSPARENT);
        root.setFocusableInTouchMode(true);
        logo = new ImageView(this);
        logo.setImageResource(ru.geministation.R.drawable.gemini_star);
        logo.setContentDescription("Gemini");
        LinearLayout.LayoutParams logoParams = new LinearLayout.LayoutParams(dp(32), dp(32));
        logoParams.gravity = Gravity.BOTTOM;
        root.addView(logo, logoParams);
        text = new TextView(this);
        text.setTextColor(Color.WHITE);
        text.setTextSize(16);
        text.setGravity(Gravity.CENTER_VERTICAL);
        text.setPadding(dp(12), dp(8), dp(12), dp(8));
        text.setMaxWidth(dp(420));
        text.setMaxLines(4);
        text.setEllipsize(TextUtils.TruncateAt.END);
        GradientDrawable background = new GradientDrawable();
        background.setColor(0xd91b1926);
        background.setCornerRadius(dp(12));
        text.setBackground(background);
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(-2, -2);
        textParams.gravity = Gravity.BOTTOM;
        textParams.setMarginStart(dp(10));
        root.addView(text, textParams);
        setContentView(root);
        getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        getWindow().setGravity(Gravity.BOTTOM | Gravity.START);
        getWindow().setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT);
        root.requestFocus();
        showText("Слушаю…");
    }
    private void showText(String value) { text.setText(value); }
    private void startSpinner() {
        if (spin != null && spin.isStarted()) return;
        spin = ObjectAnimator.ofFloat(logo, View.ROTATION, 0f, 360f);
        spin.setDuration(1200);
        spin.setInterpolator(new LinearInterpolator());
        spin.setRepeatCount(ObjectAnimator.INFINITE);
        spin.start();
    }
    private void stopSpinner() {
        if (spin != null) { spin.cancel(); spin = null; }
        logo.setRotation(0);
    }
    private void showErrorAndClose(String message) {
        AppLog.add("Диалог: " + message);
        stopSpinner();
        showText(message);
        flow.finish();
        cancelRecognition();
        stopSpeaking();
        handler.postDelayed(() -> { if (!destroyed) finish(); }, 5000);
    }
    private void closeForSilence() {
        AppLog.add("Диалог: пользователь не ответил; завершение");
        flow.finish();
        cancelRecognition();
        stopSpeaking();
        finish();
    }
    private JSONObject turn(String role, String value) throws Exception {
        return new JSONObject().put("role", role).put("parts", new JSONArray().put(new JSONObject().put("text", value)));
    }
    private void submit(String question) {
        if (!flow.recognized()) return;
        showText(question);
        startSpinner();
        final String apiKey;
        try { apiKey = secrets.load(); }
        catch (Exception e) { showErrorAndClose("Не удалось прочитать API-ключ. Проверьте веб-настройки."); return; }
        if (apiKey.isEmpty()) { showErrorAndClose("Добавьте API-ключ в веб-интерфейсе на порту 6529."); return; }
        final JSONArray request;
        try { request = new JSONArray(history.toString()); request.put(turn("user", question)); }
        catch (Exception e) { showErrorAndClose("Не удалось подготовить запрос."); return; }
        String model = AppSettings.model(this);
        AppLog.add("Диалог: запрос к Gemini начат");
        executor.execute(() -> {
            try {
                String response = client.generate(apiKey, model, request);
                runOnUiThread(() -> {
                    if (destroyed || !resumed || !flow.answered()) return;
                    try {
                        history.put(turn("user", question)); history.put(turn("model", response));
                        while (history.length() > 20) { history.remove(0); history.remove(0); }
                    } catch (Exception ignored) { }
                    stopSpinner();
                    showText(response);
                    AppLog.add("Диалог: текст ответа получен; начинаем синтез");
                    speak(apiKey, response);
                });
            } catch (Exception e) {
                AppLog.add("Текст: ошибка обработки " + e.getClass().getSimpleName());
                runOnUiThread(() -> {
                    if (destroyed || flow.phase() != ConversationFlow.Phase.THINKING) return;
                    String message = e instanceof java.io.IOException && e.getMessage() != null && e.getMessage().startsWith("Gemini API: HTTP")
                            ? e.getMessage() : "Ошибка запроса. Проверьте сеть, WebShift и Gemini API.";
                    showErrorAndClose(message);
                });
            }
        });
    }
    private void stopSpeaking() {
        speechGeneration.incrementAndGet();
        voicePlayer.stop();
        if (client != null) client.cancelSpeech();
    }
    private void speak(String apiKey, String response) {
        final int generation = speechGeneration.incrementAndGet();
        speechExecutor.execute(() -> {
            try {
                try (VoicePlayer.Stream stream = voicePlayer.openStream(() -> speechGeneration.get() == generation)) {
                    try {
                        client.synthesizeStreaming(apiKey, response, stream::write);
                        if (speechGeneration.get() != generation) return;
                        stream.drain();
                    } catch (GeminiClient.StreamingUnavailableException unavailable) {
                        AppLog.add("TTS: потоковый режим недоступен, используем обычный синтез");
                        PcmAudio audio = client.synthesize(apiKey, response);
                        if (speechGeneration.get() != generation) return;
                        voicePlayer.play(audio, () -> speechGeneration.get() == generation);
                    }
                }
                if (speechGeneration.get() != generation) return;
                AppLog.add("TTS: воспроизведение завершено");
                runOnUiThread(() -> {
                    if (!destroyed && resumed && speechGeneration.get() == generation)
                        handler.postDelayed(() -> { if (!destroyed && resumed && speechGeneration.get() == generation) beginFollowUp(); }, 350);
                });
            } catch (Exception e) {
                AppLog.add("TTS: ошибка обработки " + e.getClass().getSimpleName());
                runOnUiThread(() -> {
                    if (destroyed || !resumed || speechGeneration.get() != generation) return;
                    String detail = e instanceof java.io.IOException && e.getMessage() != null && e.getMessage().startsWith("Gemini TTS: HTTP")
                            ? " (" + e.getMessage() + ")" : "";
                    AppLog.add("TTS: синтез не удался" + detail + "; ответ оставлен на экране");
                    // Keep the answer visible briefly, then allow a follow-up even if audio failed.
                    handler.postDelayed(() -> { if (!destroyed && resumed && speechGeneration.get() == generation) beginFollowUp(); }, 2000);
                });
            }
        });
    }
    private void beginFollowUp() {
        if (!flow.followUp()) return;
        AppLog.add("Диалог: ожидание ответа пользователя");
        startListening(true);
    }
    private void startInitial() {
        if (!resumed || destroyed || permissionPending || flow.phase() != ConversationFlow.Phase.NEW) return;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissionPending = true;
            AppLog.add("Речь: запрашиваем разрешение микрофона");
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, MICROPHONE_REQUEST);
            return;
        }
        try {
            if (secrets.load().isEmpty()) { showErrorAndClose("Добавьте API-ключ в веб-интерфейсе на порту 6529."); return; }
        } catch (Exception e) { showErrorAndClose("Не удалось прочитать API-ключ. Проверьте веб-настройки."); return; }
        initialStartPending = false;
        if (flow.initialListening()) startListening(false);
    }
    private long recognitionElapsed() { return SystemClock.elapsedRealtime() - recognitionStarted; }
    private void clearRecognitionTimeout() {
        if (recognitionTimeout != null) handler.removeCallbacks(recognitionTimeout);
        recognitionTimeout = null;
    }
    private void finishRecognition() { clearRecognitionTimeout(); listening = false; waitingForFinal = false; }
    private void cancelRecognition() {
        recognitionAttempt++;
        clearRecognitionTimeout();
        if (liveTranscriber != null) { liveTranscriber.cancel(); liveTranscriber = null; }
        if (recognizer != null && listening) { try { recognizer.cancel(); } catch (RuntimeException ignored) { } }
        listening = false; waitingForFinal = false;
    }
    private void scheduleNoSpeechTimeout(final int attempt, long delay) {
        clearRecognitionTimeout();
        recognitionTimeout = () -> {
            if (!listening || attempt != recognitionAttempt || heardSpeech) return;
            AppLog.add("Речь #" + attempt + ": тишина " + recognitionElapsed() + " мс");
            closeForSilence();
        };
        handler.postDelayed(recognitionTimeout, delay);
    }
    private void scheduleSpeechDeadline(final int attempt) {
        clearRecognitionTimeout();
        recognitionTimeout = () -> {
            if (!listening || attempt != recognitionAttempt || waitingForFinal) return;
            AppLog.add("Речь #" + attempt + ": лимит длительности фразы; запрашиваем итог");
            finishVoiceInput();
        };
        handler.postDelayed(recognitionTimeout, MAX_UTTERANCE_MS);
    }
    private void scheduleFinalTimeout(final int attempt) {
        clearRecognitionTimeout();
        recognitionTimeout = () -> {
            if (!listening || attempt != recognitionAttempt) return;
            AppLog.add("Речь #" + attempt + ": нет итогового результата 15 с после завершения фразы");
            showErrorAndClose("Распознаватель не ответил. Попробуйте ещё раз.");
        };
        handler.postDelayed(recognitionTimeout, FINAL_RESULT_TIMEOUT_MS);
    }
    private void finishVoiceInput() {
        if (!listening || waitingForFinal) return;
        if (liveTranscriber == null && recognizer == null) return;
        waitingForFinal = true;
        speechEnded = SystemClock.elapsedRealtime();
        AppLog.add("Речь #" + recognitionAttempt + ": ручное завершение через " + recognitionElapsed() + " мс");
        scheduleFinalTimeout(recognitionAttempt);
        if (liveTranscriber != null) { liveTranscriber.finishInput(); return; }
        try { recognizer.stopListening(); }
        catch (RuntimeException e) { AppLog.add("Речь: ошибка stopListening " + e.getClass().getSimpleName()); showErrorAndClose("Не удалось завершить распознавание."); }
    }
    private void startListening(final boolean followUp) {
        if (destroyed || !resumed || listening) return;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { showErrorAndClose("Нет доступа к микрофону."); return; }
        final int attempt = ++recognitionAttempt;
        recognitionStarted = SystemClock.elapsedRealtime(); speechEnded = 0; partialCount = 0; heardSpeech = false;
        // Keep the answer on screen during follow-up silence; replace it as soon as speech begins.
        if (!followUp) showText("Слушаю…");
        if ("gemini_live".equals(AppSettings.recognition(this))) {
            startLiveRecognition(attempt, followUp);
            return;
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) { showErrorAndClose("Распознаватель речи недоступен."); return; }
        try {
            String component = Settings.Secure.getString(getContentResolver(), "voice_recognition_service");
            ComponentName name = component == null ? null : ComponentName.unflattenFromString(component);
            if (recognizer == null) recognizer = name == null ? SpeechRecognizer.createSpeechRecognizer(this) : SpeechRecognizer.createSpeechRecognizer(this, name);
            AppLog.add("Речь #" + attempt + ": запуск; служба " + (name == null ? "системная по умолчанию" : name.flattenToShortString()) + (followUp ? "; продолжение" : "; первый запрос"));
            recognizer.setRecognitionListener(new RecognitionListener() {
                private boolean active() { return listening && recognitionAttempt == attempt; }
                public void onReadyForSpeech(Bundle params) {
                    if (!active()) return;
                    AppLog.add("Речь #" + attempt + ": микрофон готов через " + recognitionElapsed() + " мс");
                    if (!heardSpeech && !waitingForFinal) scheduleNoSpeechTimeout(attempt, followUp ? FOLLOW_UP_SILENCE_MS : INITIAL_SILENCE_MS);
                }
                public void onBeginningOfSpeech() {
                    if (!active()) return;
                    boolean firstSpeech = !heardSpeech;
                    heardSpeech = true;
                    if (!waitingForFinal) scheduleSpeechDeadline(attempt);
                    AppLog.add("Речь #" + attempt + ": начало речи через " + recognitionElapsed() + " мс");
                    if (firstSpeech && !waitingForFinal) showText("Слушаю…");
                }
                public void onRmsChanged(float rmsdB) { }
                public void onBufferReceived(byte[] buffer) { }
                public void onEndOfSpeech() {
                    if (!active()) return;
                    if (speechEnded == 0) speechEnded = SystemClock.elapsedRealtime();
                    waitingForFinal = true;
                    AppLog.add("Речь #" + attempt + ": конец фразы через " + recognitionElapsed() + " мс");
                    scheduleFinalTimeout(attempt);
                }
                public void onError(int error) {
                    if (!active()) return;
                    AppLog.add("Речь #" + attempt + ": ошибка " + error + " (" + recognitionErrorName(error) + ") через " + recognitionElapsed() + " мс");
                    finishRecognition();
                    if (error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT || error == SpeechRecognizer.ERROR_NO_MATCH) closeForSilence();
                    else showErrorAndClose("Ошибка распознавания речи (код " + error + ").");
                }
                public void onResults(Bundle results) {
                    if (!active()) return;
                    long finalDelay = speechEnded == 0 ? -1 : SystemClock.elapsedRealtime() - speechEnded;
                    ArrayList<String> texts = results == null ? null : results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    String recognized = texts == null || texts.isEmpty() || texts.get(0) == null ? "" : texts.get(0).trim();
                    AppLog.add("Речь #" + attempt + ": итог через " + recognitionElapsed() + " мс; после конца "
                            + (finalDelay < 0 ? "неизвестно" : finalDelay + " мс") + "; частичных " + partialCount
                            + "; вариантов " + (texts == null ? 0 : texts.size()) + "; символов в первом варианте " + recognized.length());
                    finishRecognition();
                    if (recognized.isEmpty()) {
                        AppLog.add("Речь #" + attempt + ": системная служба вернула пустую расшифровку");
                        if (followUp) closeForSilence();
                        else showErrorAndClose("Распознаватель станции вернул пустой результат. Попробуйте Gemini Live в веб-настройках.");
                        return;
                    }
                    submit(recognized);
                }
                public void onPartialResults(Bundle partialResults) {
                    if (!active() || partialResults == null) return;
                    ArrayList<String> texts = partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    if (texts == null || texts.isEmpty() || texts.get(0) == null || texts.get(0).trim().isEmpty()) return;
                    boolean firstSpeech = !heardSpeech;
                    heardSpeech = true;
                    // Don't replace a final-result timeout when partials arrive late.
                    if (firstSpeech && !waitingForFinal) scheduleSpeechDeadline(attempt);
                    partialCount++;
                    if (partialCount == 1) AppLog.add("Речь #" + attempt + ": первый промежуточный результат через " + recognitionElapsed() + " мс");
                    showText(texts.get(0));
                }
                public void onEvent(int eventType, Bundle params) { }
            });
            Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU");
            intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
            intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 750L);
            intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 600L);
            intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
            listening = true; waitingForFinal = false;
            // Also bound a recognizer that never emits onReadyForSpeech.
            scheduleNoSpeechTimeout(attempt, followUp ? FOLLOW_UP_SILENCE_MS + 4000 : INITIAL_SILENCE_MS);
            recognizer.startListening(intent);
        } catch (RuntimeException e) {
            AppLog.add("Речь #" + attempt + ": ошибка запуска " + e.getClass().getSimpleName());
            finishRecognition(); showErrorAndClose("Не удалось запустить распознавание речи.");
        }
    }
    private void startLiveRecognition(final int attempt, final boolean followUp) {
        final String key;
        try { key = secrets.load(); }
        catch (Exception e) { showErrorAndClose("Не удалось прочитать API-ключ."); return; }
        if (key.isEmpty()) { showErrorAndClose("Добавьте API-ключ в веб-интерфейсе на порту 6529."); return; }
        listening = true; waitingForFinal = false;
        AppLog.add("Речь #" + attempt + ": режим Gemini Live" + (followUp ? "; продолжение" : "; первый запрос"));
        liveTranscriber = new LiveTranscriber(client, key, new LiveTranscriber.Listener() {
            private boolean active() { return listening && recognitionAttempt == attempt; }
            public void onReady() {
                if (!active()) return;
                AppLog.add("Речь #" + attempt + ": прямой микрофон готов через " + recognitionElapsed() + " мс");
                if (!heardSpeech && !waitingForFinal) scheduleNoSpeechTimeout(attempt, followUp ? FOLLOW_UP_SILENCE_MS : INITIAL_SILENCE_MS);
            }
            public void onPartial(String value) {
                if (!active()) return;
                boolean firstSpeech = !heardSpeech;
                heardSpeech = true;
                if (firstSpeech && !waitingForFinal) scheduleSpeechDeadline(attempt);
                partialCount++;
                if (partialCount == 1) AppLog.add("Речь #" + attempt + ": первая промежуточная расшифровка через " + recognitionElapsed() + " мс");
                showText(value);
            }
            public void onFinal(String value) {
                if (!active()) return;
                AppLog.add("Речь #" + attempt + ": итог Gemini Live через " + recognitionElapsed() + " мс; частичных " + partialCount);
                finishRecognition();
                liveTranscriber = null;
                submit(value);
            }
            public void onError(String message) {
                if (!active()) return;
                AppLog.add("Речь #" + attempt + ": ошибка Gemini Live");
                finishRecognition();
                liveTranscriber = null;
                showErrorAndClose(message + ". Подробности — в веб-логах.");
            }
        });
        // Connection startup is separate from the no-speech timer; a slow DNS lookup must not end the dialog.
        clearRecognitionTimeout();
        recognitionTimeout = () -> {
            if (!listening || attempt != recognitionAttempt) return;
            AppLog.add("Речь #" + attempt + ": таймаут подключения Gemini Live (20 с)");
            showErrorAndClose("Не удалось подключить Gemini Live. Проверьте сеть и логи.");
        };
        handler.postDelayed(recognitionTimeout, 20000);
        liveTranscriber.start();
    }
    private static String recognitionErrorName(int error) {
        switch (error) {
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT: return "таймаут сети";
            case SpeechRecognizer.ERROR_NETWORK: return "сеть";
            case SpeechRecognizer.ERROR_AUDIO: return "аудио";
            case SpeechRecognizer.ERROR_SERVER: return "сервер";
            case SpeechRecognizer.ERROR_CLIENT: return "клиент";
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT: return "нет речи";
            case SpeechRecognizer.ERROR_NO_MATCH: return "не распознано";
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY: return "служба занята";
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS: return "нет прав";
            default: return "неизвестно";
        }
    }
    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request != MICROPHONE_REQUEST) return;
        permissionPending = false;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            if (resumed) startInitial();
        } else { initialStartPending = false; showErrorAndClose("Для голосового диалога нужен доступ к микрофону."); }
    }
    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) { finish(); return true; }
        if (keyCode == KeyEvent.KEYCODE_VOICE_ASSIST || keyCode == KeyEvent.KEYCODE_SEARCH || keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            if (listening) finishVoiceInput();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }
    @Override protected void onResume() { super.onResume(); resumed = true; if (initialStartPending && !permissionPending) startInitial(); }
    @Override protected void onStop() {
        resumed = false;
        super.onStop();
        if (listening) AppLog.add("Речь #" + recognitionAttempt + ": отмена при уходе с экрана через " + recognitionElapsed() + " мс");
        cancelRecognition(); stopSpeaking(); stopSpinner();
        // Don't leave an invisible assistant listening or waiting on a network request.
        if (!permissionPending && !isChangingConfigurations()) finish();
    }
    @Override protected void onDestroy() {
        destroyed = true; flow.finish();
        handler.removeCallbacksAndMessages(null);
        cancelRecognition();
        if (recognizer != null) recognizer.destroy();
        stopSpeaking();
        client.cancel(); executor.shutdownNow(); speechExecutor.shutdownNow();
        super.onDestroy();
    }
}
