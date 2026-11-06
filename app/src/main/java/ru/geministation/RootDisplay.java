package ru.geministation;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Movie;
import android.os.SystemClock;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Opt-in framebuffer renderer. No system services are stopped and no system files are modified. */
final class RootDisplay {
    private static final int WIDTH = 25, HEIGHT = 16, FRAME_MS = 100;
    private final Context context;
    private final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor();
    private Session session;
    RootDisplay(Context context) { this.context = context.getApplicationContext(); }
    static boolean enabled(Context context) {
        return context.getSharedPreferences("led_output", Context.MODE_PRIVATE).getBoolean("root", false);
    }
    static boolean setEnabled(Context context, boolean value) {
        return context.getSharedPreferences("led_output", Context.MODE_PRIVATE).edit().putBoolean("root", value).commit();
    }
    synchronized void play(String mode) {
        if (!LedAnimations.mode(mode)) return;
        if (session == null || session.closed.get()) {
            session = new Session();
            session.setMode(mode);
            Thread thread = new Thread(session, "GeminiRootDisplay"); thread.setDaemon(true); thread.start();
        } else session.setMode(mode);
    }
    synchronized void stop() {
        if (session != null) { session.cancel(); session = null; }
    }
    private static String quote(String path) { return "'" + path.replace("'", "'\"'\"'") + "'"; }
    private final class Session implements Runnable {
        private final AtomicBoolean closed = new AtomicBoolean();
        private volatile Process process;
        private volatile String mode;
        private volatile int revision;
        void setMode(String value) { mode = value; revision++; }
        void cancel() {
            closed.set(true);
            Process p = process;
            if (p != null) p.destroyForcibly();
        }
        ScheduledFuture<?> timeout() {
            return watchdog.schedule(() -> {
                if (closed.compareAndSet(false, true)) {
                    Process p = process; if (p != null) p.destroyForcibly();
                    AppLog.add("Экран root: нет ответа от помощника; работа остановлена");
                }
            }, 8, TimeUnit.SECONDS);
        }
        @Override public void run() {
            Bitmap bitmap = null;
            try {
                String apk = context.getApplicationInfo().sourceDir;
                String library = context.getApplicationInfo().nativeLibraryDir + "/libgemini_display.so";
                String command = "CLASSPATH=" + quote(apk) + " exec /system/bin/app_process /system/bin ru.geministation.RootDisplayHelper " + quote(library);
                if (closed.get()) return;
                Process p = new ProcessBuilder("su", "-c", command).start(); process = p;
                if (closed.get()) { p.destroyForcibly(); return; }
                Thread errors = new Thread(() -> { try (InputStream stream = p.getErrorStream()) { byte[] b = new byte[512]; while (stream.read(b) != -1) { } } catch (Exception ignored) { } }, "GeminiRootDisplayStderr");
                errors.setDaemon(true); errors.start();
                InputStream input = p.getInputStream(); OutputStream output = p.getOutputStream();
                ScheduledFuture<?> timer = timeout(); int status;
                try { status = input.read(); } finally { timer.cancel(false); }
                if (closed.get()) return;
                if (status != 0) throw new IllegalStateException(status == 1 ? "нет доступа к fb4" : status == 2 ? "неподдерживаемый формат или ioctl fb4" : "root-помощник не запустился");
                AppLog.add("Экран root: помощник подключён; запись не подтверждает видимое изображение");
                bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(bitmap); int[] pixels = new int[WIDTH * HEIGHT];
                byte[] frame = new byte[WIDTH * HEIGHT * 3];
                Movie movie = null; int loaded = -1; long started = 0;
                while (!closed.get()) {
                    int current = revision;
                    if (current != loaded) {
                        byte[] gif = LedAnimations.current(context, mode);
                        movie = Movie.decodeByteArray(gif, 0, gif.length);
                        if (movie == null || movie.width() != WIDTH || movie.height() != HEIGHT) throw new IllegalStateException("GIF не декодирован");
                        loaded = current; started = SystemClock.elapsedRealtime();
                    }
                    long at = SystemClock.elapsedRealtime();
                    int duration = Math.max(1, movie.duration());
                    movie.setTime((int) ((at - started) % duration));
                    canvas.drawColor(Color.BLACK); movie.draw(canvas, 0, 0);
                    bitmap.getPixels(pixels, 0, WIDTH, 0, 0, WIDTH, HEIGHT);
                    for (int i = 0; i < pixels.length; i++) {
                        frame[i * 3] = (byte) Color.red(pixels[i]);
                        frame[i * 3 + 1] = (byte) Color.green(pixels[i]);
                        frame[i * 3 + 2] = (byte) Color.blue(pixels[i]);
                    }
                    if (closed.get()) break;
                    timer = timeout();
                    try { output.write(frame); output.flush(); status = input.read(); } finally { timer.cancel(false); }
                    if (closed.get()) break;
                    if (status != 0) throw new IllegalStateException("ошибка записи fb4");
                    long remaining = FRAME_MS - (SystemClock.elapsedRealtime() - at);
                    if (remaining > 0) Thread.sleep(remaining);
                }
            } catch (Exception | LinkageError e) {
                if (!closed.get()) AppLog.add("Экран root: " + e.getClass().getSimpleName() + (e instanceof IllegalStateException ? ": " + e.getMessage() : "") + "; проверьте разрешение root, fb4 и журнал системы");
            } finally {
                cancel();
                Process p = process;
                if (p != null) {
                    try { p.getOutputStream().close(); } catch (Exception ignored) { }
                    try { p.getInputStream().close(); } catch (Exception ignored) { }
                    try { p.getErrorStream().close(); } catch (Exception ignored) { }
                }
                if (bitmap != null) bitmap.recycle();
            }
        }
    }
}
