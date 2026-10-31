package ru.geministation.buttonhelper;

import android.os.Process;
import android.os.SystemClock;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/** Explicitly started as shell over ADB; never reads notification contents. */
public final class MediaAccessGuard {
    private static final String COMPONENT = "ru.geministation/.MediaAccessService";
    private static final String LOCK = "/data/local/tmp/gemini-media-guard.lock";
    private static final long CHECK_MS = 3000;

    public static void main(String[] args) {
        try (RandomAccessFile lockFile = new RandomAccessFile(LOCK, "rw");
             FileLock lock = lockFile.getChannel().tryLock()) {
            if (lock == null) { System.err.println("Media guard is already running"); return; }
            System.out.println("Media guard started; pid=" + Process.myPid());
            run();
        } catch (Exception e) {
            System.err.println("Media guard stopped: " + e.getClass().getSimpleName());
        }
    }
    private static void run() throws InterruptedException {
        MediaGuardPolicy policy = new MediaGuardPolicy();
        MediaGuardPolicy.Access last = null;
        long lastAttemptLog = -600_000L;
        while (true) {
            MediaGuardPolicy.Access state = readStatus();
            long now = SystemClock.elapsedRealtime();
            if (state != last) {
                System.out.println("Media access: " + state.name().toLowerCase(java.util.Locale.ROOT));
                last = state;
            }
            if (policy.shouldGrant(state, now)) {
                boolean commandRan = allowListener();
                // Android 9 may return exit 0 even on an exception. Only a later status
                // of GRANTED proves the permission was restored.
                if (now - lastAttemptLog >= 600_000L) {
                    System.out.println("Media access: restore " + (commandRan ? "requested" : "command failed")
                            + "; awaiting status confirmation");
                    lastAttemptLog = now;
                }
            }
            Thread.sleep(CHECK_MS);
        }
    }
    private static MediaGuardPolicy.Access readStatus() {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL("http://127.0.0.1:6529/api/media/access").openConnection(Proxy.NO_PROXY);
            connection.setConnectTimeout(1500);
            connection.setReadTimeout(1500);
            connection.setInstanceFollowRedirects(false);
            if (connection.getResponseCode() != 200) return MediaGuardPolicy.Access.UNKNOWN;
            // The fixed endpoint returns one short ASCII word and no sensitive data.
            try (BufferedReader in = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.US_ASCII))) {
                char[] chars = new char[32];
                int total = 0, n;
                while (total < chars.length && (n = in.read(chars, total, chars.length - total)) != -1) total += n;
                return total < chars.length ? MediaGuardPolicy.parse(new String(chars, 0, total)) : MediaGuardPolicy.Access.UNKNOWN;
            }
        } catch (Exception e) { return MediaGuardPolicy.Access.UNKNOWN; }
        finally { if (connection != null) connection.disconnect(); }
    }
    private static boolean allowListener() {
        java.lang.Process command = null;
        try {
            command = new ProcessBuilder("/system/bin/cmd", "notification", "allow_listener", COMPONENT, "0")
                    .redirectErrorStream(true).start();
            if (!command.waitFor(5, TimeUnit.SECONDS)) { command.destroyForcibly(); return false; }
            return command.exitValue() == 0;
        } catch (Exception e) { return false; }
        finally { if (command != null) { try { command.getInputStream().close(); } catch (Exception ignored) { } } }
    }
    private MediaAccessGuard() { }
}
