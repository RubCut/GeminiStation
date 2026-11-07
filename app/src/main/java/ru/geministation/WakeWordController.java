package ru.geministation;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Local keyword spotting. Lifecycle operations run serially; capture has a separate thread. */
final class WakeWordController {
    private static WakeWordController instance;
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicBoolean firing = new AtomicBoolean();
    private volatile boolean serviceRunning, dialogActive;
    private volatile String status = "Ожидание запуска веб-сервера";
    private volatile int rms = -1, peak = -1;
    private volatile WakeCapture capture;
    // Worker-owned bounded backoff; a persistently broken microphone must not spin forever.
    private int unexpectedRestarts;
    private long captureStartedAt;

    static synchronized WakeWordController get(Context context) {
        if (instance == null) instance = new WakeWordController(context.getApplicationContext());
        return instance;
    }
    private WakeWordController(Context context) { this.context = context; }
    String status() { return status; }
    int rms() { return rms; }
    int peak() { return peak; }
    void serviceStarted() {
        serviceRunning = true;
        worker.execute(() -> {
            int removed = LegacyWakeCleanup.remove(context.getFilesDir());
            if (removed > 0) AppLog.add("Удалены файлы прежней локальной модели: " + removed);
            reconcile();
        });
    }
    void serviceStopped() { serviceRunning = false; worker.execute(this::reconcile); }
    void settingsChanged() { worker.execute(() -> { unexpectedRestarts = 0; reconcile(); }); }
    void personalSamplesChanged() { worker.execute(() -> { if (stopCapture()) reconcile(); }); }
    void dialogOpening() { dialogActive = true; worker.execute(this::reconcile); }
    void pauseForDialog(Consumer<Boolean> continuation) {
        dialogActive = true;
        worker.execute(() -> {
            boolean released = stopCapture();
            if (!released) status = "Не удалось освободить микрофон";
            else status = AppSettings.wakeEnabled(context) ? "Пауза на время диалога" : "Выключена";
            main.post(() -> continuation.accept(released));
        });
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
            if (!stopCapture()) { status = "Не удалось освободить микрофон"; return; }
            if (!AppSettings.wakeEnabled(context)) status = "Выключена";
            else if (!serviceRunning) status = "Веб-сервер остановлен";
            else if (dialogActive) status = "Пауза на время диалога";
            else status = "Нет разрешения на микрофон";
            return;
        }
        String sensitivity = AppSettings.wakeSensitivity(context), engine = AppSettings.wakeEngine(context);
        boolean filter = AppSettings.wakeFilter(context), amplify = AppSettings.wakeAmplify(context), vad = AppSettings.wakeVad(context), personal = AppSettings.wakePersonal(context);
        VoiceTuning tuning = VoiceTuning.load(context);
        WakeCapture current = capture;
        if (current != null) {
            if (current.isAlive() && current.sameSettings(engine, sensitivity, filter, amplify, vad, personal, tuning)) return;
            if (!stopCapture()) { status = "Не удалось освободить микрофон"; return; }
        }
        status = "Подготавливаем локальную модель";
        try {
            if (!wanted()) { reconcile(); return; }
            WakeCapture next = new WakeCapture(context, new WakeCapture.Listener() {
                @Override public void onLevel(WakeCapture owner, int level, int max) {
                    if (capture != owner || !wanted()) return;
                    rms = level; peak = max;
                }
                @Override public void onTrigger(WakeCapture owner) {
                    if (capture != owner || !wanted() || !firing.compareAndSet(false, true)) return;
                    final long detectedAt = SystemClock.elapsedRealtime();
                    AppLog.add("Голосовая активация: ключевое слово обнаружено");
                    dialogActive = true;
                    worker.execute(() -> {
                        boolean released = stopCapture();
                        AppLog.add("Голосовая активация: передача микрофона " + (SystemClock.elapsedRealtime() - detectedAt) + " мс после обнаружения");
                        main.post(() -> {
                            firing.set(false);
                            if (!released) {
                                status = "Не удалось освободить микрофон";
                                AppLog.add("Голосовая активация: микрофон не освобождён; диалог не запущен");
                                dialogActive = false; return;
                            }
                            if (!serviceRunning || !AppSettings.wakeEnabled(context)) { dialogActive = false; settingsChanged(); return; }
                            try {
                                Intent intent = new Intent(AssistantInvocations.ASSIST).setClass(context, MainActivity.class)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        .putExtra(MainActivity.EXTRA_WAKE_DETECTED_AT, detectedAt);
                                context.startActivity(intent);
                            } catch (RuntimeException e) {
                                AppLog.add("Голосовая активация: не удалось открыть диалог: " + e.getClass().getSimpleName());
                                dialogActive = false; settingsChanged();
                            }
                        });
                    });
                }
                @Override public void onFinished(WakeCapture owner, String error) {
                    worker.execute(() -> {
                        if (capture != owner) return;
                        capture = null; rms = -1; peak = -1;
                        if (!firing.get() && wanted()) {
                            if (SystemClock.elapsedRealtime() - captureStartedAt >= 60000) unexpectedRestarts = 0;
                            if (error != null) AppLog.add("Голосовая активация: ошибка записи " + error);
                            else AppLog.add("Голосовая активация: обработчик неожиданно остановился");
                            if (unexpectedRestarts < 3) {
                                long delay = 3000L << unexpectedRestarts++;
                                status = "Микрофон остановился; повторный запуск через " + delay / 1000 + " с";
                                main.postDelayed(() -> worker.execute(() -> { if (capture == null && wanted()) reconcile(); }), delay);
                            } else status = "Ошибка микрофона; проверьте журнал и включите активацию заново";
                        }
                    });
                }
            }, engine, sensitivity, filter, amplify, vad, personal, tuning);
            capture = next; rms = -1; peak = -1;
            next.start(); captureStartedAt = SystemClock.elapsedRealtime();
            if (!wanted()) { reconcile(); return; }
            status = "Микрофон запущен (" + next.sourceName() + ", " + engine + ")";
            AppLog.add("Голосовая активация: микрофон запущен (" + next.sourceName() + "), движок " + engine + ", чувствительность " + sensitivity + ", фильтр " + filter + ", усиление " + amplify + ", проверка речи " + vad + ", личные образцы " + personal);
        } catch (Exception | LinkageError e) {
            stopCapture();
            status = "Ошибка запуска; проверьте журнал";
            AppLog.add("Голосовая активация: ошибка запуска " + engine + ": " + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : " — " + e.getMessage()));
        }
    }
    private boolean stopCapture() {
        WakeCapture current = capture;
        rms = -1; peak = -1;
        if (current == null) return true;
        if (!current.stopAndWait()) {
            AppLog.add("Голосовая активация: таймаут остановки микрофона");
            return false;
        }
        if (capture == current) capture = null;
        AppLog.add("Голосовая активация: микрофон освобождён");
        return true;
    }
}
