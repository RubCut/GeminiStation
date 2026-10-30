package ru.geministation;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import edu.cmu.pocketsphinx.Hypothesis;
import edu.cmu.pocketsphinx.RecognitionListener;
import edu.cmu.pocketsphinx.SpeechRecognizer;
import edu.cmu.pocketsphinx.SpeechRecognizerSetup;
import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Local keyword spotting; all native recognizer operations run serially off the UI thread. */
final class WakeWordController {
    private static final String SEARCH = "gemini-wake";
    private static WakeWordController instance;
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicBoolean firing = new AtomicBoolean();
    private volatile boolean serviceRunning, dialogActive;
    private volatile String status = "Ожидание запуска веб-сервера";
    private SpeechRecognizer recognizer; // worker thread only
    private volatile SpeechRecognizer activeRecognizer;

    static synchronized WakeWordController get(Context context) {
        if (instance == null) instance = new WakeWordController(context.getApplicationContext());
        return instance;
    }
    private WakeWordController(Context context) { this.context = context; }
    String status() { return status; }
    void serviceStarted() { serviceRunning = true; worker.execute(this::reconcile); }
    void serviceStopped() { serviceRunning = false; worker.execute(this::reconcile); }
    void settingsChanged() { worker.execute(this::reconcile); }
    void dialogOpening() { dialogActive = true; worker.execute(this::reconcile); }
    void pauseForDialog(Runnable continuation) {
        dialogActive = true;
        worker.execute(() -> { reconcile(); main.post(continuation); });
    }
    void dialogClosed() {
        dialogActive = false;
        // LiveTranscriber's recording thread releases its AudioRecord asynchronously.
        main.postDelayed(() -> worker.execute(this::reconcile), 1000);
    }
    private boolean wanted() {
        return WakeWordPolicy.shouldListen(serviceRunning, AppSettings.wakeEnabled(context), dialogActive,
                context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED);
    }
    private void reconcile() {
        if (!wanted()) {
            destroyRecognizer();
            if (!AppSettings.wakeEnabled(context)) status = "Выключена";
            else if (!serviceRunning) status = "Веб-сервер остановлен";
            else if (dialogActive) status = "Пауза на время диалога";
            else status = "Нет разрешения на микрофон";
            return;
        }
        if (recognizer != null) return;
        status = "Подготавливаем локальную модель";
        try {
            File model = WakeWordAssets.prepare(context);
            if (!wanted()) { reconcile(); return; }
            SpeechRecognizer next = SpeechRecognizerSetup.defaultSetup()
                    .setAcousticModel(model).setDictionary(new File(model, "words.dict"))
                    .setSampleRate(16000).setKeywordThreshold(1e-15f)
                    .setString("-fdict", new File(model, "noisedict").getAbsolutePath())
                    .setString("-lda", new File(model, "feature_transform").getAbsolutePath())
                    .setBoolean("-backtrace", false).getRecognizer();
            recognizer = next;
            next.addKeywordSearch(SEARCH, new File(model, "keywords.list"));
            next.addListener(new RecognitionListener() {
                @Override public void onBeginningOfSpeech() { }
                @Override public void onEndOfSpeech() { }
                @Override public void onResult(Hypothesis hypothesis) { }
                @Override public void onTimeout() { }
                @Override public void onPartialResult(Hypothesis hypothesis) {
                    if (activeRecognizer != next || !wanted() || hypothesis == null || !WakeWordResult.matches(hypothesis.getHypstr())) return;
                    if (!firing.compareAndSet(false, true)) return;
                    AppLog.add("Голосовая активация: ключевое слово обнаружено");
                    dialogActive = true;
                    worker.execute(() -> {
                        destroyRecognizer();
                        main.post(() -> {
                            firing.set(false);
                            if (!serviceRunning || !AppSettings.wakeEnabled(context)) { dialogActive = false; settingsChanged(); return; }
                            try {
                                Intent intent = new Intent(AssistantInvocations.ASSIST).setClass(context, MainActivity.class)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                                context.startActivity(intent);
                            } catch (RuntimeException e) {
                                AppLog.add("Голосовая активация: не удалось открыть диалог: " + e.getClass().getSimpleName());
                                dialogActive = false; settingsChanged();
                            }
                        });
                    });
                }
                @Override public void onError(Exception error) {
                    if (activeRecognizer != next) return;
                    AppLog.add("Голосовая активация: ошибка микрофона " + error.getClass().getSimpleName());
                    worker.execute(() -> { if (recognizer == next) { destroyRecognizer(); status = "Ошибка микрофона; проверьте журнал"; } });
                }
            });
            if (!wanted()) { reconcile(); return; }
            activeRecognizer = next;
            if (!next.startListening(SEARCH)) throw new IllegalStateException("recognizer already active");
            status = "Локальный обработчик запущен";
            AppLog.add("Голосовая активация: локальный обработчик запущен");
        } catch (Exception | LinkageError e) {
            destroyRecognizer();
            status = "Ошибка запуска; проверьте журнал";
            AppLog.add("Голосовая активация: ошибка запуска " + e.getClass().getSimpleName());
        }
    }
    private void destroyRecognizer() {
        SpeechRecognizer current = recognizer;
        recognizer = null; activeRecognizer = null;
        if (current == null) return;
        try { current.cancel(); } catch (RuntimeException ignored) { }
        try { current.shutdown(); } catch (RuntimeException ignored) { }
        try { current.getDecoder().delete(); } catch (RuntimeException ignored) { }
        AppLog.add("Голосовая активация: микрофон освобождён");
    }
}
