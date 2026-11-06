package ru.geministation;

import android.Manifest;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Intent;
import android.content.Context;
import android.content.SharedPreferences;
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
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public final class MainActivity extends Activity {
    static final String EXTRA_WAKE_DETECTED_AT = "ru.geministation.WAKE_DETECTED_AT";

    private static final int MICROPHONE_REQUEST = 100;
    private static final long FINAL_RESULT_TIMEOUT_MS = 15000;
    private static final long MAX_UTTERANCE_MS = 45000;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final ExecutorService speechExecutor = Executors.newSingleThreadExecutor();
    private final AtomicInteger speechGeneration = new AtomicInteger();
    private final VoicePlayer voicePlayer = new VoicePlayer();
    private AndroidTtsPlayer androidTts;
    private PromptContextPrefetch promptContext;
    private long answerStarted;
    private SoundCuePlayer soundCuePlayer;
    private BackgroundAudioFocus backgroundAudioFocus;
    private SharedPreferences preferences;
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
    private ImageView logo, mediaIcon;
    private ThinkingIndicator thinkingIndicator;
    private boolean closeAfterResponse;
    private ObjectAnimator spin, pulse;
    private LedController led;
    private RootRing ring;
    private Runnable recognitionTimeout;
    private long recognitionStarted;
    private int recognitionAttempt, partialCount, handoffGeneration;
    private boolean waitingForFinal, heardSpeech, listening, cueComplete, micReady, liveConnected;
    private VoiceTuning voiceTuning;
    private volatile boolean destroyed, resumed;
    private boolean initialStartPending = true, permissionPending, micHandoffReady;

    private final SharedPreferences.OnSharedPreferenceChangeListener preferenceListener = (prefs, key) -> {
        if (!"audio_duck".equals(key)) return;
        handler.post(() -> {
            if (destroyed) return;
            if (!AppSettings.audioDuckEnabled(this)) backgroundAudioFocus.release();
            else if (resumed && (flow.phase() == ConversationFlow.Phase.LISTENING
                    || flow.phase() == ConversationFlow.Phase.THINKING || flow.phase() == ConversationFlow.Phase.SPEAKING))
                backgroundAudioFocus.acquire();
        });
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        WakeWordController.get(this).dialogOpening();
        keyVault = ApiKeyVault.get(this);
        soundCuePlayer = new SoundCuePlayer(this);
        long wakeDetectedAt = getIntent().getLongExtra(EXTRA_WAKE_DETECTED_AT, 0);
        if (wakeDetectedAt > 0 && wakeDetectedAt <= SystemClock.elapsedRealtime()
                && SystemClock.elapsedRealtime() - wakeDetectedAt < 30000) {
            soundCuePlayer.setWakeDetectedAt(wakeDetectedAt);
            AppLog.add("Голосовая активация: экран создан через " + (SystemClock.elapsedRealtime() - wakeDetectedAt) + " мс после обнаружения");
        }
        androidTts = new AndroidTtsPlayer(this);
        promptContext = new PromptContextPrefetch(this);
        promptContext.refresh();
        backgroundAudioFocus = new BackgroundAudioFocus(this);
        preferences = getSharedPreferences("MainActivity", Context.MODE_PRIVATE);
        preferences.registerOnSharedPreferenceChangeListener(preferenceListener);
        deviceActions = new DeviceActions(this);
        client = new GeminiClient(getApplicationContext());
        led = LedController.get(this);
        ring = RootRing.get(this);
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
            closeAfterResponse = false; // A new explicit invocation overrides the prior model decision.
            beginFollowUp();
        }
    }
    private int dp(int value) { return (int) (value * getResources().getDisplayMetrics().density + .5f); }
    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setGravity(Gravity.BOTTOM | Gravity.START);
        // Expand the bubble around the existing logo position rather than moving it.
        root.setPadding(dp(16), dp(0), dp(16), dp(16));
        root.setBackgroundColor(Color.TRANSPARENT);
        root.setFocusableInTouchMode(true);
        LinearLayout bubble = new LinearLayout(this);
        bubble.setOrientation(LinearLayout.HORIZONTAL);
        bubble.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        bubble.setPadding(dp(12), dp(8), dp(12), dp(8));
        GradientDrawable background = new GradientDrawable();
        background.setColor(0xf2272530);
        background.setCornerRadius(dp(28));
        background.setStroke(dp(1), 0xff4b4655);
        bubble.setBackground(background);
        root.addView(bubble, new LinearLayout.LayoutParams(-2, -2));
        logo = new ImageView(this);
        logo.setImageResource(ru.geministation.R.drawable.gemini_star);
        logo.setContentDescription("Gemini");
        FrameLayout logoFrame = new FrameLayout(this);
        LinearLayout.LayoutParams logoParams = new LinearLayout.LayoutParams(dp(42), dp(42));
        logoParams.gravity = Gravity.CENTER_VERTICAL;
        bubble.addView(logoFrame, logoParams);
        thinkingIndicator = new ThinkingIndicator(this);
        logoFrame.addView(thinkingIndicator, new FrameLayout.LayoutParams(-1, -1));
        logoFrame.addView(logo, new FrameLayout.LayoutParams(dp(32), dp(32), Gravity.CENTER));
        text = new TextView(this);
        text.setTypeface(BrandFont.get(this));
        text.setTextColor(Color.WHITE);
        text.setTextSize(16);
        text.setGravity(Gravity.CENTER_VERTICAL);
        // Preserve the generous space above and below the text.
        text.setPadding(dp(2), dp(8), dp(4), dp(8));
        text.setMaxWidth(dp(420));
        text.setMaxLines(4);
        text.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(-2, -2);
        textParams.gravity = Gravity.CENTER_VERTICAL;
        textParams.setMarginStart(dp(12));
        mediaIcon = new ImageView(this);
        mediaIcon.setVisibility(View.GONE);
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(40), dp(40));
        iconParams.gravity = Gravity.CENTER_VERTICAL;
        iconParams.setMarginStart(dp(14));
        bubble.addView(mediaIcon, iconParams);
        bubble.addView(text, textParams);
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
    private void showText(String value) {
        mediaIcon.setVisibility(View.GONE); text.setVisibility(View.VISIBLE); text.setText(value);
    }
    private void showMediaIcon(MediaCommands.Command command) {
        int drawable;
        switch (command) {
            case PLAY: drawable = R.drawable.media_play; break;
            case PAUSE: drawable = R.drawable.media_pause; break;
            case STOP: drawable = R.drawable.media_stop; break;
            case NEXT: drawable = R.drawable.media_next; break;
            case PREVIOUS: drawable = R.drawable.media_previous; break;
            case FAST_FORWARD: drawable = R.drawable.media_fast_forward; break;
            case REWIND: drawable = R.drawable.media_rewind; break;
            default: throw new IllegalArgumentException("Unknown media command");
        }
        stopSpinner(); text.setVisibility(View.GONE);
        mediaIcon.setImageResource(drawable);
        mediaIcon.setContentDescription("Команда отправлена: " + command.description + "; выполнение плеером не подтверждено");
        mediaIcon.setVisibility(View.VISIBLE);
    }
    private void showMediaCard(String title, String packageName, int fallback, String service) {
        stopSpinner();
        try { mediaIcon.setImageDrawable(getPackageManager().getApplicationIcon(packageName)); }
        catch (Exception e) { mediaIcon.setImageResource(fallback); }
        mediaIcon.setContentDescription(service);
        mediaIcon.setVisibility(View.VISIBLE); text.setVisibility(View.VISIBLE); text.setText(title);
    }
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
        led.show("thinking"); ring.show("thinking");
        if (spin != null && spin.isStarted()) return;
        thinkingIndicator.start();
        spin = ObjectAnimator.ofFloat(logo, View.ROTATION, 0f, 360f);
        spin.setDuration(1200);
        spin.setInterpolator(new LinearInterpolator());
        spin.setRepeatCount(ObjectAnimator.INFINITE);
        spin.start();
    }
    private void stopSpinner() {
        if (spin != null) { spin.cancel(); spin = null; }
        thinkingIndicator.stop();
        logo.setRotation(0);
    }
    private void showErrorAndClose(String message) {
        AppLog.add("Диалог: " + message);
        stopSpinner(); stopPulse(); led.show(null); ring.stop();
        showText(message);
        flow.finish();
        cancelRecognition();
        stopSpeaking();
        backgroundAudioFocus.release();
        handler.postDelayed(() -> { if (!destroyed) finish(); }, 5000);
    }
    private void closeForSilence() {
        ring.stop();
        AppLog.add("Диалог: пользователь не ответил; завершение");
        flow.finish();
        cancelRecognition();
        stopSpeaking();
        soundCuePlayer.play(R.raw.no_answer, "нет ответа", () -> {
            backgroundAudioFocus.release();
            if (!destroyed && resumed) finish();
        });
    }
    private JSONObject turn(String role, String value) throws Exception {
        return new JSONObject().put("role", role).put("parts", new JSONArray().put(new JSONObject().put("text", value)));
    }
    private void submit(String question) {
        if (!flow.recognized()) return;
        backgroundAudioFocus.acquire();
        showText(question);
        startSpinner();
        try {
            if (keyVault.keys().isEmpty()) { showErrorAndClose("Добавьте API-ключ в веб-интерфейсе на порту 6529."); return; }
        } catch (Exception e) { showErrorAndClose("Не удалось прочитать API-ключи. Проверьте веб-настройки."); return; }
        final JSONArray request;
        try { request = new JSONArray(history.toString()); request.put(turn("user", question)); }
        catch (Exception e) { showErrorAndClose("Не удалось подготовить запрос."); return; }
        String model = AppSettings.model(this);
        answerStarted = SystemClock.elapsedRealtime();
        AppLog.add("Диалог: запрос к Gemini начат");
        executor.execute(() -> {
            try {
                PromptContextPrefetch.Result prepared = promptContext.get();
                VisibleApps apps = prepared.apps;
                JSONArray appList = apps.promptList();
                final SmartHome.Snapshot home = prepared.home;
                AppLog.add("Диалог: контекст готов через " + (SystemClock.elapsedRealtime() - answerStarted) + " мс");
                final JSONArray homeList = home.prompt();
                if (prepared.homeUnavailable) homeList.put(new JSONObject().put("status", "Список устройств временно недоступен; не выдумывай устройства, попроси повторить позже."));
                final java.util.List<PluginExecution.Tool> pluginTools = PluginExecution.snapshot(getApplicationContext());
                final JSONArray pluginDeclarations = PluginExecution.declarations(pluginTools);
                GeminiToolProtocol.Result first = keyVault.run(KeyRotationDomains.Domain.TEXT, () -> !destroyed && resumed,
                        key -> client.generate(key, model, request, appList, homeList, pluginDeclarations));
                PluginExecution.Tool pluginTool = PluginExecution.find(pluginTools, first.call);
                GeminiToolProtocol.Result completed = first;
                if (pluginTool != null && first.callPart != null && !destroyed && resumed) {
                    AppLog.add("Плагины: выполнение подтверждённого инструмента");
                    final JSONObject pluginResult = PluginExecution.execute(getApplicationContext(), pluginTool, first.call);
                    // Retries rotate only the follow-up request, never repeat plugin side effects.
                    completed = keyVault.run(KeyRotationDomains.Domain.TEXT, () -> !destroyed && resumed,
                            key -> client.pluginFollowup(key, model, request, appList, homeList, pluginDeclarations, first.callPart, pluginResult));
                }
                final GeminiToolProtocol.Result result = completed;
                DeviceToolSpec.Action action = result.call == null ? null : DeviceToolSpec.parse(result.call, apps.size(), home.devices.size());
                if (result.call != null) AppLog.add("Диалог: выбранная функция " + (action == null ? "отклонена" : action.kind.name()));
                if (action != null && action.kind == DeviceToolSpec.Kind.OPEN_LINK
                        && !UserLinkAuthorization.presentInUserTurns(action.data, request)) action = null;
                boolean search = action != null && action.kind == DeviceToolSpec.Kind.SEARCH_YOUTUBE;
                final String searchQuery = search ? action.data : null;
                final String searchPackage = search ? apps.get(action.number).component.getPackageName() : null;
                boolean foundVideo = false, nativeSearch = false;
                if (search) {
                    String url;
                    if (YoutubeSearchProtocol.smartTube(apps.get(action.number).component.getPackageName())) {
                        // SmartTube handles this as a native search-and-play request without another API key.
                        url = YoutubeSearchProtocol.resultsUrl(action.data, true);
                        nativeSearch = true;
                    } else {
                        try {
                            final String query = action.data;
                            url = keyVault.run(KeyRotationDomains.Domain.YOUTUBE, () -> !destroyed && resumed, key -> client.searchYoutube(key, query));
                            foundVideo = true;
                        } catch (Exception e) {
                            // The Data API may be disabled/restricted. Open search results instead, never an invented video.
                            AppLog.add("YouTube: поиск видео недоступен; пробуем страницу результатов (" + e.getClass().getSimpleName() + ")");
                            url = YoutubeSearchProtocol.resultsUrl(action.data, false);
                        }
                    }
                    action = new DeviceToolSpec.Action(DeviceToolSpec.Kind.OPEN_LINK, action.number, url);
                }
                String networkText = null;
                YandexCloud.Track music = null;
                if (action != null && (action.kind == DeviceToolSpec.Kind.HOME_CONTROL || action.kind == DeviceToolSpec.Kind.HOME_SET) && !destroyed && resumed) {
                    SmartHome.Device device = home.devices.get(action.number);
                    try {
                        boolean ok;
                        if (action.kind == DeviceToolSpec.Kind.HOME_SET) {
                            int split = action.data.indexOf(':');
                            ok = SmartHome.setCapability(getApplicationContext(), home, device, Integer.parseInt(action.data.substring(0, split)), action.data.substring(split + 1));
                        } else ok = SmartHome.set(getApplicationContext(), home, device, "on".equals(action.data));
                        networkText = ok ? ("Яндекс подтвердил команду устройству «" + device.name + "».") : "Яндекс не подтвердил выполнение команды умного дома.";
                    } catch (Exception e) {
                        AppLog.add("Умный дом: ошибка команды (" + YandexCloud.safeError(e) + ")");
                        networkText = e instanceof SmartHome.ControlDenied
                                ? "Яндекс отказал в управлении. Возможно, токену не хватает права iot control. Проверьте настройки умного дома."
                                : "Не удалось выполнить команду умного дома. Проверьте доступ и журнал.";
                    }
                } else if (action != null && action.kind == DeviceToolSpec.Kind.YANDEX_MUSIC && !destroyed && resumed) {
                    try {
                        music = YandexCloud.search(getApplicationContext(), action.data, action.number == 1 ? "album" : action.number == 2 ? "playlist" : "track");
                        networkText = "Нашёл " + music.title + ". Пробую включить на станции.";
                    } catch (Exception e) {
                        AppLog.add("Музыка: поиск недоступен (" + YandexCloud.safeError(e) + ")");
                        networkText = "Не удалось найти запись в Яндекс Музыке. Проверьте вход в Яндекс и журнал.";
                    }
                }
                final String remoteText = networkText;
                final YandexCloud.Track foundTrack = music;
                final DeviceToolSpec.Action chosen = action;
                final boolean videoSearch = search, videoFound = foundVideo, nativeVideoSearch = nativeSearch;
                runOnUiThread(() -> {
                    if (destroyed || !resumed || !flow.answered()) return;
                    String response = result.text;
                    boolean mediaDispatched = false;
                    closeAfterResponse = result.finishDialog;
                    if (result.call != null) {
                        if (remoteText != null) {
                            response = remoteText;
                            pendingNavigation = null;
                        } else {
                            DeviceActions.Outcome outcome = deviceActions.prepare(chosen, apps);
                            response = outcome.text;
                            mediaDispatched = outcome.mediaDispatched;
                            if (videoSearch && outcome.navigation != null)
                                response = nativeVideoSearch ? "Ищу и открываю видео в SmartTube." :
                                        (videoFound ? "Открываю найденное видео." : "Открываю результаты поиска YouTube.");
                            pendingNavigation = outcome.navigation;
                        }
                    }
                    try {
                        history.put(turn("user", question)); history.put(turn("model", response));
                        while (history.length() > 20) { history.remove(0); history.remove(0); }
                    } catch (Exception ignored) { }
                    AppLog.add("Диалог: решение после ответа — " + (closeAfterResponse ? "завершить" : "продолжить"));
                    if (mediaDispatched && chosen != null && chosen.kind == DeviceToolSpec.Kind.MEDIA_CONTROL) {
                        MediaCommands.Command command = MediaCommands.parse(chosen.data);
                        if (command != null) { mediaAcknowledgement(command); return; }
                    }
                    if (foundTrack != null) {
                        showMediaCard(foundTrack.title, "ru.yandex.music", R.drawable.yandex_music_fallback, "Яндекс Музыка");
                        final int generation = speechGeneration.incrementAndGet();
                        led.playMusic(foundTrack.id, foundTrack.type, foundTrack.account,
                                () -> !destroyed && resumed && speechGeneration.get() == generation, sent -> {
                            if (destroyed || !resumed || speechGeneration.get() != generation || flow.phase() != ConversationFlow.Phase.SPEAKING) return;
                            if (!sent) { showText("Станция не приняла команду Яндекс Музыки или недоступна. Проверьте подключение и журнал.");
                                speak("Станция не приняла команду Яндекс Музыки или недоступна. Проверьте подключение и журнал."); return; }
                            pendingNavigation = () -> true; // A queued command is not proof of playback.
                            confirmMedia(1400);
                        });
                        return;
                    }
                    if (nativeVideoSearch && pendingNavigation != null) {
                        showMediaCard(searchQuery, searchPackage, R.drawable.media_play, "SmartTube");
                        confirmMedia(1400);
                        return;
                    }
                    // Keep the thinking animation until audio actually starts.
                    showText(response);
                    AppLog.add("Диалог: текст ответа получен через " + (SystemClock.elapsedRealtime() - answerStarted) + " мс; начинаем синтез");
                    speak(response);
                });
            } catch (Exception e) {
                AppLog.add("Текст: ошибка обработки " + e.getClass().getSimpleName());
                runOnUiThread(() -> {
                    if (destroyed || flow.phase() != ConversationFlow.Phase.THINKING) return;
                    String message = e instanceof java.io.IOException && e.getMessage() != null && e.getMessage().startsWith("Gemini API: HTTP")
                            ? e.getMessage() : "Ошибка запроса. Проверьте сеть, настройки DNS и Gemini API.";
                    showErrorAndClose(message);
                });
            }
        });
    }
    private void stopSpeaking() {
        speechGeneration.incrementAndGet();
        voicePlayer.stop();
        if (androidTts != null) androidTts.stop();
        if (client != null) client.cancelSpeech();
        if (soundCuePlayer != null) soundCuePlayer.cancel();
    }
    private void mediaAcknowledgement(MediaCommands.Command command) {
        showMediaIcon(command);
        confirmMedia(350);
    }
    private void confirmMedia(long delay) {
        final int generation = speechGeneration.incrementAndGet();
        animatePulse(true); led.show("speaking"); ring.show("speaking");
        AppLog.add("Медиа: показываем карточку и воспроизводим сигнал без TTS");
        soundCuePlayer.play(R.raw.waiting_for_answer, "подтверждение медиакоманды", () -> {
            if (destroyed || !resumed || speechGeneration.get() != generation || flow.phase() != ConversationFlow.Phase.SPEAKING) return;
            backgroundAudioFocus.release();
            // Reuse the confirmation as the follow-up cue, so it is not played twice.
            handler.postDelayed(() -> { if (!destroyed && resumed && speechGeneration.get() == generation) beginFollowUp(true); }, delay);
        });
    }
    private void onPlaybackStarted(int generation) {
        runOnUiThread(() -> {
            if (destroyed || !resumed || speechGeneration.get() != generation || flow.phase() != ConversationFlow.Phase.SPEAKING) return;
            AppLog.add("Диалог: озвучка началась через " + (SystemClock.elapsedRealtime() - answerStarted) + " мс после распознавания");
            animatePulse(true); led.show("speaking"); ring.show("speaking");
        });
    }
    private void speak(String response) {
        backgroundAudioFocus.acquire();
        final int generation = speechGeneration.incrementAndGet();
        final String engine = AppSettings.ttsEngine(this);
        if (!AndroidTtsEngines.GEMINI.equals(engine)) {
            AppLog.add("TTS: выбран Android-движок");
            androidTts.speak(engine, response, new AndroidTtsPlayer.Listener() {
                @Override public void started() { onPlaybackStarted(generation); }
                @Override public void done() { speechFinished(generation); }
                @Override public void error(String reason) { speechFailed(generation, reason); }
            });
            return;
        }
        // Snapshot once per reply so streaming and fallback cannot use different voices.
        final String voice = AppSettings.voice(this);
        AppLog.add("TTS: выбран голос " + voice);
        speechExecutor.execute(() -> {
            try {
                keyVault.run(KeyRotationDomains.Domain.TTS, () -> speechGeneration.get() == generation, apiKey -> {
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
                speechFinished(generation);
            } catch (Exception e) {
                AppLog.add("TTS: ошибка обработки " + e.getClass().getSimpleName());
                String detail = e instanceof java.io.IOException && e.getMessage() != null && e.getMessage().startsWith("Gemini TTS: HTTP")
                        ? " (" + e.getMessage() + ")" : "";
                speechFailed(generation, "Синтез Gemini не удался" + detail);
            }
        });
    }
    private void speechFinished(int generation) {
        if (speechGeneration.get() != generation) return;
        AppLog.add("TTS: воспроизведение завершено");
        runOnUiThread(() -> {
            if (!destroyed && resumed && speechGeneration.get() == generation) {
                backgroundAudioFocus.release();
                handler.postDelayed(() -> { if (!destroyed && resumed && speechGeneration.get() == generation) beginFollowUp(); }, 350);
            }
        });
    }
    private void speechFailed(int generation, String reason) {
        runOnUiThread(() -> {
            if (destroyed || !resumed || speechGeneration.get() != generation) return;
            AppLog.add("TTS: " + reason + "; ответ оставлен на экране");
            stopSpinner(); stopPulse(); ring.stop();
            backgroundAudioFocus.release();
            handler.postDelayed(() -> { if (!destroyed && resumed && speechGeneration.get() == generation) beginFollowUp(); }, 2000);
        });
    }
    private void beginFollowUp() { beginFollowUp(false); }
    private void beginFollowUp(boolean cueAlreadyPlayed) {
        backgroundAudioFocus.release();
        if (pendingNavigation != null) {
            DeviceActions.Navigation navigation = pendingNavigation;
            pendingNavigation = null;
            if (!navigation.open()) showErrorAndClose("Не удалось открыть экран. Проверьте журнал.");
            else { flow.finish(); finish(); }
            return;
        }
        if (closeAfterResponse) {
            AppLog.add("Диалог: завершён по решению модели после ответа");
            flow.finish(); stopPulse(); led.show(null); finish(); return;
        }
        if (!flow.followUp()) return;
        AppLog.add("Диалог: ожидание ответа пользователя");
        startListening(true, cueAlreadyPlayed ? 0 : R.raw.waiting_for_answer, "ожидание ответа");
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
        backgroundAudioFocus.acquire();
        String keyError = null;
        try {
            if (keyVault.keys().isEmpty()) keyError = "Добавьте API-ключ в веб-интерфейсе на порту 6529.";
        } catch (Exception e) { keyError = "Не удалось прочитать API-ключ. Проверьте веб-настройки."; }
        if (keyError != null) {
            final String message = keyError;
            ring.show("activation");
            soundCuePlayer.play(R.raw.assistant_start, "активация", () -> {
                if (resumed && !destroyed) showErrorAndClose(message);
            });
            return;
        }
        if (flow.initialListening()) startListening(false, R.raw.assistant_start, "активация");
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
        if (!listening || waitingForFinal || !cueComplete) return;
        if (liveTranscriber == null) return;
        waitingForFinal = true;
        AppLog.add("Речь #" + recognitionAttempt + ": ручное завершение через " + recognitionElapsed() + " мс");
        scheduleFinalTimeout(recognitionAttempt);
        liveTranscriber.finishInput();
    }
    private void startListening(final boolean followUp, int cue, String cueLabel) {
        if (destroyed || !resumed || listening) return;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { showErrorAndClose("Нет доступа к микрофону."); return; }
        promptContext.refresh();
        final int attempt = ++recognitionAttempt;
        recognitionStarted = SystemClock.elapsedRealtime(); partialCount = 0; heardSpeech = false;
        listeningPolicy.reset();
        voiceTuning = VoiceTuning.load(this);
        cueComplete = false;
        // Keep the answer on screen during follow-up silence; replace it as soon as speech begins.
        animatePulse(false); led.show("listening");
        ring.show(!followUp && cue == R.raw.assistant_start ? "activation" : "listening");
        if (!followUp) showText("Подключаюсь…");
        final KeyRotation.Attempt keys;
        try { keys = keyVault.attempt(KeyRotationDomains.Domain.LIVE); }
        catch (Exception e) { showErrorAndClose("Не удалось прочитать API-ключи."); return; }
        if (keys.isEmpty()) { showErrorAndClose("Добавьте API-ключ в веб-интерфейсе на порту 6529."); return; }
        // Queue the local cue before DNS/client setup. Its worker can start audio
        // while Live prepares; the completion callback runs on the main thread,
        // after startLiveRecognition returns. The microphone still waits for the cue.
        Runnable afterCue = () -> {
            if (!listening || attempt != recognitionAttempt || destroyed || !resumed) return;
            cueComplete = true;
            ring.show("listening");
            if (liveTranscriber != null) {
                if (!voiceTuning.preconnect) liveTranscriber.start();
                liveTranscriber.allowCapture();
            }
        };
        if (cue != 0) soundCuePlayer.play(cue, cueLabel, afterCue);
        startLiveRecognition(attempt, followUp, keys);
        if (cue == 0) afterCue.run();
    }
    private void startLiveRecognition(final int attempt, final boolean followUp, final KeyRotation.Attempt keys) {
        final String key = keys.key();
        listening = true; waitingForFinal = false; micReady = false; liveConnected = false;
        AppLog.add("Речь #" + attempt + ": режим Gemini Live" + (followUp ? "; продолжение" : "; первый запрос"));
        liveTranscriber = new LiveTranscriber(client, key, voiceTuning, new LiveTranscriber.Listener() {
            private boolean active() { return listening && recognitionAttempt == attempt; }
            public void onReady() {
                if (!active()) return;
                AppLog.add("Речь #" + attempt + ": прямой микрофон готов через " + recognitionElapsed() + " мс");
                micReady = true;
                if (!followUp) showText(liveConnected ? "Слушаю…" : "Слушаю… Подключаюсь к Gemini");
                if (liveConnected && !heardSpeech && !waitingForFinal) scheduleNoSpeechTimeout(attempt, voiceTuning.silenceMs);
            }
            public void onConnected() {
                if (!active()) return;
                liveConnected = true;
                if (!waitingForFinal) clearRecognitionTimeout();
                if (micReady && !heardSpeech && !waitingForFinal) scheduleNoSpeechTimeout(attempt, voiceTuning.silenceMs);
            }
            public void onPartial(String value) {
                if (!active()) return;
                boolean firstSpeech = !heardSpeech;
                if (firstSpeech) backgroundAudioFocus.acquire();
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
        if (voiceTuning.preconnect || cueComplete) liveTranscriber.start();
        if (cueComplete) liveTranscriber.allowCapture();
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
        cancelRecognition(); stopSpeaking(); stopSpinner(); stopPulse(); led.show(null); ring.stop();
        backgroundAudioFocus.release();
        WakeWordController.get(this).dialogClosed();
        // Don't leave an invisible assistant listening or waiting on a network request.
        if (!permissionPending && !isChangingConfigurations()) finish();
    }
    @Override protected void onDestroy() {
        destroyed = true; flow.finish();
        led.show(null); ring.stop();
        handler.removeCallbacksAndMessages(null);
        soundCuePlayer.close();
        preferences.unregisterOnSharedPreferenceChangeListener(preferenceListener);
        backgroundAudioFocus.release();
        pendingNavigation = null;
        cancelRecognition();
        stopSpeaking();
        promptContext.close();
        client.cancel(); executor.shutdownNow(); speechExecutor.shutdownNow();
        super.onDestroy();
    }
}
