package ru.geministation;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import java.io.File;
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

    static synchronized WakeWordController get(Context context) {
        if (instance == null) instance = new WakeWordController(context.getApplicationContext());
        return instance;
    }
    private WakeWordController(Context context) { this.context = context; }
    String status() { return status; }
    int rms() { return rms; }
    int peak() { return peak; }
    void serviceStarted() { serviceRunning = true; worker.execute(this::reconcile); }
    void serviceStopped() { serviceRunning = false; worker.execute(this::reconcile); }
    void settingsChanged() { worker.execute(this::reconcile); }
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
        String sensitivity = AppSettings.wakeSensitivity(context);
        WakeCapture current = capture;
        if (current != null) {
            if (current.isAlive() && current.sensitivity().equals(sensitivity)) return;
            if (!stopCapture()) { status = "Не удалось освободить микрофон"; return; }
        }
        status = "Подготавливаем локальную модель";
        try {
            File model = WakeWordAssets.prepare(context);
            if (!wanted()) { reconcile(); return; }
            WakeCapture next = new WakeCapture(new WakeCapture.Listener() {
                @Override public void onLevel(WakeCapture owner, int level, int max) {
                    if (capture != owner || !wanted()) return;
                    rms = level; peak = max;
                }
                @Override public void onTrigger(WakeCapture owner) {
                    if (capture != owner || !wanted() || !firing.compareAndSet(false, true)) return;
                    AppLog.add("Голосовая активация: ключевое слово обнаружено");
                    dialogActive = true;
                    worker.execute(() -> {
                        boolean released = stopCapture();
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
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
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
                        if (error != null) {
                            status = "Ошибка микрофона; проверьте журнал";
                            AppLog.add("Голосовая активация: ошибка записи " + error);
                        } else if (!firing.get() && wanted()) {
                            status = "Обработчик остановился; выключите и снова включите активацию";
                            AppLog.add("Голосовая активация: обработчик неожиданно остановился");
                        }
                    });
                }
            }, sensitivity);
            capture = next; rms = -1; peak = -1;
            next.start(model);
            if (!wanted()) { reconcile(); return; }
            status = "Микрофон запущен (" + next.sourceName() + ")";
            AppLog.add("Голосовая активация: микрофон запущен (" + next.sourceName() + "), чувствительность " + sensitivity);
        } catch (Exception | LinkageError e) {
            stopCapture();
            status = "Ошибка запуска; проверьте журнал";
            AppLog.add("Голосовая активация: ошибка запуска " + e.getClass().getSimpleName());
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
