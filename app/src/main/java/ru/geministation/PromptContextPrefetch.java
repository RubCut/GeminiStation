package ru.geministation;

import android.content.Context;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Prepare device descriptions while the user is speaking; never wait indefinitely for IoT. */
final class PromptContextPrefetch {
    private final Context context;
    private final ExecutorService pool = Executors.newFixedThreadPool(2);
    private Future<VisibleApps> apps;
    private Future<SmartHome.Snapshot> home;
    private long started;
    PromptContextPrefetch(Context context) { this.context = context.getApplicationContext(); }
    synchronized void refresh() {
        long now = android.os.SystemClock.elapsedRealtime();
        if (apps != null && home != null && now - started < 30000) return;
        if (apps == null || apps.isDone()) apps = pool.submit(() -> {
            long begin = android.os.SystemClock.elapsedRealtime();
            try { return VisibleApps.load(context); }
            finally { AppLog.add("Контекст: приложения подготовлены за " + (android.os.SystemClock.elapsedRealtime() - begin) + " мс"); }
        });
        if (home == null || home.isDone()) home = pool.submit(() -> {
            long begin = android.os.SystemClock.elapsedRealtime();
            try {
                if (new SecretStore(context, SmartHome.PREFS).load().isEmpty()) return new SmartHome.Snapshot();
                return SmartHome.load(context);
            } finally { AppLog.add("Контекст: подготовка умного дома заняла " + (android.os.SystemClock.elapsedRealtime() - begin) + " мс"); }
        });
        started = now;
    }
    static final class Result {
        final VisibleApps apps; final SmartHome.Snapshot home;
        Result(VisibleApps apps, SmartHome.Snapshot home) { this.apps = apps; this.home = home; }
    }
    Result get() throws InterruptedException {
        Future<VisibleApps> appFuture; Future<SmartHome.Snapshot> homeFuture;
        synchronized (this) { appFuture = apps; homeFuture = home; }
        long end = android.os.SystemClock.elapsedRealtime() + 700;
        VisibleApps appResult = await(appFuture, end, null);
        if (appResult == null) { AppLog.add("Контекст: приложения ещё не готовы; используем пустой список"); appResult = VisibleApps.empty(); }
        SmartHome.Snapshot homeResult = await(homeFuture, end, null);
        if (homeResult == null) { AppLog.add("Контекст: умный дом ещё не готов; в этом запросе список недоступен"); homeResult = new SmartHome.Snapshot(); }
        return new Result(appResult, homeResult);
    }
    private static <T> T await(Future<T> future, long deadline, T fallback) throws InterruptedException {
        if (future == null) return fallback;
        try { return future.get(Math.max(0, deadline - android.os.SystemClock.elapsedRealtime()), TimeUnit.MILLISECONDS); }
        catch (TimeoutException e) { return fallback; }
        catch (java.util.concurrent.ExecutionException | java.util.concurrent.CancellationException e) { return fallback; }
    }
    void close() { pool.shutdownNow(); }
}
