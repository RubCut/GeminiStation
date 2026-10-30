package ru.geministation;

import android.Manifest;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public final class MainActivity extends Activity {
    private static final int MICROPHONE_REQUEST = 100;
    private static final long FINAL_RESULT_TIMEOUT_MS = 15000;
    private static final long MAX_UTTERANCE_MS = 45000;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final ExecutorService speechExecutor = Executors.newSingleThreadExecutor();
    private final AtomicInteger speechGeneration = new AtomicInteger();
    private final VoicePlayer voicePlayer = new VoicePlayer();
    private SoundCuePlayer soundCuePlayer;
    private DeviceActions deviceActions;
    private DeviceActions.Navigation pendingNavigation;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final JSONArray history = new JSONArray();
    private final ConversationFlow flow = new ConversationFlow();
    private final ListeningPolicy listeningPolicy = new ListeningPolicy();
    private GeminiClient client;
    private ApiKeyVault keyVault;
    private LiveTranscriber liveTranscriber;
    private TextView text;
    private ImageView logo;
    private ObjectAnimator spin, pulse;
    private LedController led;
    private Runnable recognitionTimeout;
    private long recognitionStarted;
    private int recognitionAttempt, partialCount, handoffGeneration;
    private boolean waitingForFinal, heardSpeech, listening;
    private volatile boolean destroyed, resumed;
    private boolean initialStartPending = true, permissionPending, micHandoffReady;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        WakeWordController.get(this).dialogOpening();
        keyVault = ApiKeyVault.get(this);
        soundCuePlayer = new SoundCuePlayer(this);
        deviceActions = new DeviceActions(this);
        client = new GeminiClient();
        led = LedController.get(this);
        buildUi();
        try { startForegroundService(new Intent(this, WebServerService.class)); }
        catch (RuntimeException e) { AppLog.add("Веб-сервер: ошибка запуска " + e.getClass().getSimpleName()); }
        AppLog.add("Диалог: ассистент открыт" + (AssistantInvocations.isInvocation(getIntent().getAction()) ? " (системный вызов)" : ""));
    }
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (!AssistantInvocations.isInvocation(intent.getAction())) return;
        AppLog.add("Диалог: повторный системный вызов ассистента");
        if (listening) finishVoiceInput();
        else if (flow.phase() == ConversationFlow.Phase.SPEAKING) {
            stopSpeaking();
            pendingNavigation = null;
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
        root.setAlpha(0f);
        root.setTranslationY(dp(12));
        root.animate().alpha(1f).translationY(0f).setDuration(350).start();
        root.requestFocus();
        showText("Подключаюсь…");
    }
    private void showText(String value) { text.setText(value); }
    private void animatePulse(boolean speaking) {
        stopSpinner();
        if (pulse != null) pulse.cancel();
        pulse = ObjectAnimator.ofPropertyValuesHolder(logo,
                PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, speaking ? 1.18f : 1.12f),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, speaking ? 1.18f : 1.12f),
                PropertyValuesHolder.ofFloat(View.ALPHA, .72f, 1f));
        pulse.setDuration(speaking ? 550 : 1100);
        pulse.setRepeatCount(ValueAnimator.INFINITE);
        pulse.setRepeatMode(ValueAnimator.REVERSE);
        pulse.start();
    }
    private void stopPulse() {
        if (pulse != null) { pulse.cancel(); pulse = null; }
        logo.setScaleX(1f); logo.setScaleY(1f); logo.setAlpha(1f);
    }
    private void startSpinner() {
        stopPulse();
        led.show("thinking");
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
        stopSpinner(); stopPulse(); led.show(null);
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
        soundCuePlayer.play(R.raw.no_answer, "нет ответа", () -> {
            if (!destroyed && resumed) finish();
        });
    }
    private JSONObject turn(String role, String value) throws Exception {
        return new JSONObject().put("role", role).put("parts", new JSONArray().put(new JSONObject().put("text", value)));
    }
    private void submit(String question) {
        if (!flow.recognized()) return;
        showText(question);
        startSpinner();
        try {
            if (keyVault.keys().isEmpty()) { showErrorAndClose("Добавьте API-ключ в веб-интерфейсе на порту 6529."); return; }
        } catch (Exception e) { showErrorAndClose("Не удалось прочитать API-ключи. Проверьте веб-настройки."); return; }
        final JSONArray request;
        try { request = new JSONArray(history.toString()); request.put(turn("user", question)); }
        catch (Exception e) { showErrorAndClose("Не удалось подготовить запрос."); return; }
        String model = AppSettings.model(this);
        AppLog.add("Диалог: запрос к Gemini начат");
        executor.execute(() -> {
            try {
                VisibleApps apps = VisibleApps.load(getApplicationContext());
                JSONArray appList = apps.promptList();
                GeminiToolProtocol.Result result = keyVault.run(() -> !destroyed && resumed, key -> client.generate(key, model, request, appList));
                DeviceToolSpec.Action action = result.call == null ? null : DeviceToolSpec.parse(result.call, apps.size());
                if (action != null && action.kind == DeviceToolSpec.Kind.OPEN_LINK
                        && !UserLinkAuthorization.presentInUserTurns(action.data, request)) action = null;
                boolean search = action != null && action.kind == DeviceToolSpec.Kind.SEARCH_YOUTUBE;
                boolean foundVideo = false;
                if (search) {
                    String url;
                    try {
                        final String query = action.data;
                        url = keyVault.run(() -> !destroyed && resumed, key -> client.searchYoutube(key, query));
                        foundVideo = true;
                    } catch (Exception e) {
                        // The Data API may be disabled/restricted. Open search results instead, never an invented video.
                        AppLog.add("YouTube: поиск видео недоступен; пробуем страницу результатов (" + e.getClass().getSimpleName() + ")");
                        url = YoutubeSearchProtocol.resultsUrl(action.data);
                    }
                    action = new DeviceToolSpec.Action(DeviceToolSpec.Kind.OPEN_LINK, action.number, url);
                }
                final DeviceToolSpec.Action chosen = action;
                final boolean videoSearch = search, videoFound = foundVideo;
                runOnUiThread(() -> {
                    if (destroyed || !resumed || !flow.answered()) return;
                    String response = result.text;
                    if (result.call != null) {
                        DeviceActions.Outcome outcome = deviceActions.prepare(chosen, apps);
                        response = outcome.text;
                        if (videoSearch && outcome.navigation != null)
                            response = videoFound ? "Открываю найденное видео." : "Открываю результаты поиска YouTube.";
                        pendingNavigation = outcome.navigation;
                    }
                    try {
                        history.put(turn("user", question)); history.put(turn("model", response));
                        while (history.length() > 20) { history.remove(0); history.remove(0); }
                    } catch (Exception ignored) { }
                    // Keep the thinking animation until audio actually starts.
                    showText(response);
                    AppLog.add("Диалог: текст ответа получен; начинаем синтез");
                    speak(response);
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
    private void onPlaybackStarted(int generation) {
        runOnUiThread(() -> {
            if (destroyed || !resumed || speechGeneration.get() != generation || flow.phase() != ConversationFlow.Phase.SPEAKING) return;
            animatePulse(true); led.show("speaking");
        });
    }
    private void speak(String response) {
        final int generation = speechGeneration.incrementAndGet();
        // Snapshot once per reply so streaming and fallback cannot use different voices.
        final String voice = AppSettings.voice(this);
        AppLog.add("TTS: выбран голос " + voice);
        speechExecutor.execute(() -> {
            try {
                keyVault.run(() -> speechGeneration.get() == generation, apiKey -> {
                    try (VoicePlayer.Stream stream = voicePlayer.openStream(() -> speechGeneration.get() == generation, () -> onPlaybackStarted(generation))) {
                        try {
                            client.synthesizeStreaming(apiKey, response, voice, stream::write);
                            if (speechGeneration.get() == generation) stream.drain();
                        } catch (GeminiClient.StreamingUnavailableException unavailable) {
                            AppLog.add("TTS: потоковый режим недоступен, используем обычный синтез");
                            PcmAudio audio = client.synthesize(apiKey, response, voice);
                            if (speechGeneration.get() == generation)
                                voicePlayer.play(audio, () -> speechGeneration.get() == generation, () -> onPlaybackStarted(generation));
                        }
                    }
                    return null;
                });
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
                    stopSpinner(); stopPulse();
                    // Keep the answer visible briefly, then allow a follow-up even if audio failed.
                    handler.postDelayed(() -> { if (!destroyed && resumed && speechGeneration.get() == generation) beginFollowUp(); }, 2000);
                });
            }
        });
    }
    private void beginFollowUp() {
        if (pendingNavigation != null) {
            DeviceActions.Navigation navigation = pendingNavigation;
            pendingNavigation = null;
            if (!navigation.open()) showErrorAndClose("Не удалось открыть экран. Проверьте журнал.");
            else { flow.finish(); finish(); }
            return;
        }
        if (!flow.followUp()) return;
        AppLog.add("Диалог: ожидание ответа пользователя");
        soundCuePlayer.play(R.raw.waiting_for_answer, "ожидание ответа", () -> {
            if (!destroyed && resumed && flow.phase() == ConversationFlow.Phase.FOLLOW_UP) startListening(true);
        });
    }
    private void startInitial() {
        if (!resumed || destroyed || !micHandoffReady || permissionPending || flow.phase() != ConversationFlow.Phase.NEW) return;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissionPending = true;
            AppLog.add("Речь: запрашиваем разрешение микрофона");
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, MICROPHONE_REQUEST);
            return;
        }
        initialStartPending = false;
        // Confirmation of activation, not an indication that Gemini Live is ready yet.
        // Play before opening Live's microphone so the cue isn't sent for transcription.
        soundCuePlayer.play(R.raw.assistant_start, "активация", () -> {
            if (!resumed || destroyed || flow.phase() != ConversationFlow.Phase.NEW) return;
            try {
                if (keyVault.keys().isEmpty()) { showErrorAndClose("Добавьте API-ключ в веб-интерфейсе на порту 6529."); return; }
            } catch (Exception e) { showErrorAndClose("Не удалось прочитать API-ключ. Проверьте веб-настройки."); return; }
            if (flow.initialListening()) startListening(false);
        });
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
        listening = false; waitingForFinal = false;
    }
    private void scheduleNoSpeechTimeout(final int attempt, long delay) {
        clearRecognitionTimeout();
        recognitionTimeout = () -> {
            if (!listening || attempt != recognitionAttempt || heardSpeech) return;
            if (listeningPolicy.grantGrace(liveTranscriber != null && liveTranscriber.hasRecentSignal(350))) {
                AppLog.add("Речь #" + attempt + ": сигнал у границы тайм-аута; ждём расшифровку ещё 1 с");
                scheduleNoSpeechTimeout(attempt, ListeningPolicy.SIGNAL_GRACE_MS);
                return;
            }
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
        if (liveTranscriber == null) return;
        waitingForFinal = true;
        AppLog.add("Речь #" + recognitionAttempt + ": ручное завершение через " + recognitionElapsed() + " мс");
        scheduleFinalTimeout(recognitionAttempt);
        liveTranscriber.finishInput();
    }
    private void startListening(final boolean followUp) {
        if (destroyed || !resumed || listening) return;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { showErrorAndClose("Нет доступа к микрофону."); return; }
        final int attempt = ++recognitionAttempt;
        recognitionStarted = SystemClock.elapsedRealtime(); partialCount = 0; heardSpeech = false;
        listeningPolicy.reset();
        // Keep the answer on screen during follow-up silence; replace it as soon as speech begins.
        animatePulse(false); led.show("listening");
        if (!followUp) showText("Подключаюсь…");
        final KeyRotation.Attempt keys;
        try { keys = keyVault.rotation.attempt(keyVault.keys()); }
        catch (Exception e) { showErrorAndClose("Не удалось прочитать API-ключи."); return; }
        if (keys.isEmpty()) { showErrorAndClose("Добавьте API-ключ в веб-интерфейсе на порту 6529."); return; }
        startLiveRecognition(attempt, followUp, keys);
    }
    private void startLiveRecognition(final int attempt, final boolean followUp, final KeyRotation.Attempt keys) {
        final String key = keys.key();
        listening = true; waitingForFinal = false;
        AppLog.add("Речь #" + attempt + ": режим Gemini Live" + (followUp ? "; продолжение" : "; первый запрос"));
        liveTranscriber = new LiveTranscriber(client, key, new LiveTranscriber.Listener() {
            private boolean active() { return listening && recognitionAttempt == attempt; }
            public void onReady() {
                if (!active()) return;
                AppLog.add("Речь #" + attempt + ": прямой микрофон готов через " + recognitionElapsed() + " мс");
                if (!followUp) showText("Слушаю…");
                if (!heardSpeech && !waitingForFinal) scheduleNoSpeechTimeout(attempt, ListeningPolicy.NO_SPEECH_MS);
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
            public void onError(String message, boolean rateLimited, boolean retrySafe) {
                if (!active()) return;
                boolean hasNext = rateLimited && keys.advance();
                if (rateLimited && retrySafe && hasNext && !waitingForFinal) {
                    AppLog.add("Gemini Live: лимит 429, переключаем API-ключ");
                    liveTranscriber = null;
                    startLiveRecognition(attempt, followUp, keys);
                    return;
                }
                AppLog.add("Речь #" + attempt + ": ошибка Gemini Live");
                finishRecognition();
                liveTranscriber = null;
                if (rateLimited && !retrySafe) message = "Лимит Gemini Live (429). Повторите запрос";
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
    @Override protected void onResume() {
        super.onResume(); resumed = true; micHandoffReady = false;
        final int handoff = ++handoffGeneration;
        // The background detector must release the mic before any cue or Gemini Live starts.
        WakeWordController.get(this).pauseForDialog(released -> {
            if (!resumed || destroyed || handoff != handoffGeneration) return;
            if (!released) { showErrorAndClose("Не удалось освободить микрофон для диалога."); return; }
            micHandoffReady = true;
            if (initialStartPending && !permissionPending) startInitial();
        });
    }
    @Override protected void onStop() {
        resumed = false; micHandoffReady = false; handoffGeneration++;
        super.onStop();
        if (listening) AppLog.add("Речь #" + recognitionAttempt + ": отмена при уходе с экрана через " + recognitionElapsed() + " мс");
        soundCuePlayer.cancel();
        pendingNavigation = null;
        cancelRecognition(); stopSpeaking(); stopSpinner(); stopPulse(); led.show(null);
        WakeWordController.get(this).dialogClosed();
        // Don't leave an invisible assistant listening or waiting on a network request.
        if (!permissionPending && !isChangingConfigurations()) finish();
    }
    @Override protected void onDestroy() {
        destroyed = true; flow.finish();
        led.show(null);
        handler.removeCallbacksAndMessages(null);
        soundCuePlayer.cancel();
        pendingNavigation = null;
        cancelRecognition();
        stopSpeaking();
        client.cancel(); executor.shutdownNow(); speechExecutor.shutdownNow();
        super.onDestroy();
    }
}
