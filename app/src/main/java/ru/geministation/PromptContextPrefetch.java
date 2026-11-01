package ru.geministation;

import android.content.Context;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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
        final VisibleApps apps; final SmartHome.Snapshot home; final boolean homeUnavailable;
        Result(VisibleApps apps, SmartHome.Snapshot home, boolean homeUnavailable) {
            this.apps = apps; this.home = home; this.homeUnavailable = homeUnavailable;
        }
    }
    Result get() throws InterruptedException {
        Future<VisibleApps> appFuture; Future<SmartHome.Snapshot> homeFuture;
        synchronized (this) { appFuture = apps; homeFuture = home; }
        long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(700);
        VisibleApps appResult = DeadlineWait.get(appFuture, end, null);
        if (appResult == null) { AppLog.add("Контекст: приложения ещё не готовы; используем пустой список"); appResult = VisibleApps.empty(); }
        SmartHome.Snapshot homeResult = DeadlineWait.get(homeFuture, end, null);
        if (homeResult == null) {
            homeResult = SmartHome.cachedForCurrentIdentity(context);
            if (homeResult != null) AppLog.add("Контекст: используем предыдущий список умного дома");
        }
        boolean unavailable = homeResult == null;
        if (unavailable) { AppLog.add("Контекст: умный дом ещё не готов; в этом запросе список недоступен"); homeResult = new SmartHome.Snapshot(); }
        return new Result(appResult, SmartHome.filtered(context, homeResult), unavailable);
    }
    void close() { pool.shutdownNow(); }
}
