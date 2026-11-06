package ru.geministation;

import android.content.Context;
import android.os.SystemClock;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Opt-in root ring renderer; isolated from the microphone and dialog UI threads. */
final class RootRing {
    private static final int FRAME_MS = 50;
    private static RootRing instance;
    static synchronized RootRing get(Context c) {
        if (instance == null) instance = new RootRing(c.getApplicationContext());
        return instance;
    }
    private final Context context;
    private final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor();
    private Session session;
    private String active;
    private RootRing(Context context) { this.context = context; }
    static boolean enabled(Context c) { return c.getSharedPreferences("ring_output", Context.MODE_PRIVATE).getBoolean("enabled", false); }
    static boolean monochrome(Context c) { return c.getSharedPreferences("ring_output", Context.MODE_PRIVATE).getBoolean("monochrome", false); }
    static boolean save(Context c, boolean enabled, boolean monochrome) {
        return c.getSharedPreferences("ring_output", Context.MODE_PRIVATE).edit().putBoolean("enabled", enabled).putBoolean("monochrome", monochrome).commit();
    }
    synchronized void show(String phase) {
        if (!RingAnimator.validPhase(phase)) throw new IllegalArgumentException("phase");
        active = phase;
        if (!enabled(context)) return;
        if (session == null || session.closed.get() || session.stopping) {
            if (session != null) session.abort();
            session = new Session(); session.phase = phase; session.mono = monochrome(context);
            Thread t = new Thread(session, "GeminiRootRing"); t.setDaemon(true); t.start();
        } else { session.phase = phase; session.mono = monochrome(context); }
    }
    synchronized void settingsChanged() {
        if (!enabled(context)) { if (session != null) session.requestStop(); }
        else if (active != null) show(active);
    }
    synchronized void stop() {
        active = null;
        if (session != null) session.requestStop();
    }
    private static String quote(String path) { return "'" + path.replace("'", "'\"'\"'") + "'"; }
    private final class Session implements Runnable {
        final AtomicBoolean closed = new AtomicBoolean();
        volatile Process process;
        volatile boolean stopping, ready, mono;
        volatile String phase;
        void abort() { closed.set(true); Process p = process; if (p != null) p.destroyForcibly(); }
        void requestStop() {
            stopping = true;
            if (!ready) { abort(); return; }
            watchdog.schedule(() -> { if (!closed.get()) abort(); }, 1500, TimeUnit.MILLISECONDS);
        }
        ScheduledFuture<?> timeout() {
            return watchdog.schedule(() -> {
                if (closed.compareAndSet(false, true)) {
                    Process p = process; if (p != null) p.destroyForcibly();
                    AppLog.add("Кольцо root: помощник не ответил; работа остановлена");
                }
            }, 8, TimeUnit.SECONDS);
        }
        @Override public void run() {
            try {
                String apk = context.getApplicationInfo().sourceDir;
                String library = context.getApplicationInfo().nativeLibraryDir + "/libgemini_display.so";
                String command = "CLASSPATH=" + quote(apk) + " exec /system/bin/app_process /system/bin ru.geministation.RootRingHelper " + quote(library);
                if (closed.get()) return;
                Process p = new ProcessBuilder("su", "-c", command).start(); process = p;
                if (closed.get()) { p.destroyForcibly(); return; }
                Thread errors = new Thread(() -> { try (InputStream stream = p.getErrorStream()) { byte[] b = new byte[512]; while (stream.read(b) != -1) { } } catch (Exception ignored) { } }, "GeminiRootRingStderr");
                errors.setDaemon(true); errors.start();
                InputStream input = p.getInputStream(); OutputStream output = p.getOutputStream();
                ScheduledFuture<?> timer = timeout(); int status;
                try { status = input.read(); } finally { timer.cancel(false); }
                if (closed.get()) return;
                if (status != 0) throw new IllegalStateException(status == 1 ? "нет доступа к fb5" : status == 2 ? "неподдерживаемый формат или ioctl fb5" : "root-помощник не запустился");
                ready = true;
                AppLog.add("Кольцо root: помощник подключён; видимый результат не подтверждён");
                RingAnimator animator = new RingAnimator(SystemClock.elapsedRealtime());
                String rendered = "activation";
                while (!closed.get() && !stopping) {
                    long at = SystemClock.elapsedRealtime();
                    String next = phase;
                    if (!next.equals(rendered)) { animator.setPhase(next, at); rendered = next; }
                    byte[] frame = animator.frame(at, mono);
                    if (closed.get() || stopping) break;
                    timer = timeout();
                    try { output.write(frame); output.flush(); status = input.read(); } finally { timer.cancel(false); }
                    if (closed.get() || stopping) break;
                    if (status != 0) throw new IllegalStateException("ошибка записи fb5");
                    // Wait a full interval after the completed write; slow writes must
                    // not be followed by a burst of near-simultaneous frames.
                    if (!closed.get() && !stopping) Thread.sleep(FRAME_MS);
                }
                // Graceful stop: one bounded black frame before releasing the framebuffer.
                // Another system component may immediately repaint it.
                if (stopping && !closed.get()) {
                    timer = timeout();
                    try { output.write(new byte[RingAnimator.FRAME_BYTES]); output.flush(); input.read(); }
                    finally { timer.cancel(false); }
                }
            } catch (Exception | LinkageError e) {
                if (!closed.get() && !stopping) AppLog.add("Кольцо root: " + e.getClass().getSimpleName() + (e instanceof IllegalStateException ? ": " + e.getMessage() : "") + "; проверьте root, fb5 и журнал системы");
            } finally {
                abort(); Process p = process;
                if (p != null) {
                    try { p.getOutputStream().close(); } catch (Exception ignored) { }
                    try { p.getInputStream().close(); } catch (Exception ignored) { }
                    try { p.getErrorStream().close(); } catch (Exception ignored) { }
                }
            }
        }
    }
}
