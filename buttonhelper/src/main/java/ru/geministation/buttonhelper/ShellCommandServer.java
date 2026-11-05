package ru.geministation.buttonhelper;

import android.os.Process;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.SecureRandom;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import ru.geministation.helperprotocol.HelperProtocol;

/** Loopback-only channel, authenticated with an app-private key, verified APK signatures, and pinned helper APK. */
final class ShellCommandServer implements Runnable {
    @Override public void run() {
        if (Process.myUid() != 2000) { System.err.println("ADB helper: shell UID required"); return; }
        final byte[] pinned;
        try { pinned = PackageIdentity.snapshot(); } catch (Exception e) { System.err.println("ADB helper: package identity unavailable; command server disabled"); return; }
        ThreadPoolExecutor workers = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(2), task -> { Thread t = new Thread(task, "helper-request"); t.setDaemon(true); return t; });
        try (ServerSocket server = new ServerSocket()) {
            server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), HelperProtocol.PORT), 4);
            System.out.println("ADB helper: command server ready (loopback only)");
            while (true) {
                Socket socket = server.accept();
                try { workers.execute(() -> handle(socket, pinned)); }
                catch (RejectedExecutionException e) { try { socket.close(); } catch (Exception ignored) { } }
            }
        } catch (Exception e) {
            System.err.println("ADB helper: command server unavailable (" + e.getClass().getSimpleName() + ")");
        } finally { workers.shutdownNow(); }
    }
    private static long lastReport = -60000;
    private static synchronized void reportFailure(String stage) {
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - lastReport >= 60000) {
            System.err.println("ADB helper: request failed at " + stage);
            lastReport = now;
        }
    }
    private void handle(Socket socket, byte[] pinned) {
        byte[] key = null;
        String stage = "identity";
        try (Socket peer = socket) {
            if (!peer.getInetAddress().isLoopbackAddress()) return;
            peer.setSoTimeout(3000);
            if (!HelperAuthPolicy.sameHelper(PackageIdentity.snapshot(), pinned)) { reportFailure("identity"); return; }
            stage = "private key";
            key = readAppKey();
            stage = "authentication";
            byte[] nonce = new byte[HelperProtocol.KEY_SIZE]; new SecureRandom().nextBytes(nonce);
            DataOutputStream out = new DataOutputStream(peer.getOutputStream());
            DataInputStream in = new DataInputStream(peer.getInputStream());
            HelperProtocol.writeHello(out, key, nonce);
            HelperProtocol.Request request = HelperProtocol.readRequest(in, key, nonce);
            stage = "execution";
            HelperProtocol.Result result = ShellExecutor.run("/system/bin/sh", request);
            HelperProtocol.writeResult(out, result, key, nonce);
        } catch (Exception ignored) {
            // Never log keys, command text, stdout/stderr, package data or exception messages.
            reportFailure(stage);
        } finally { if (key != null) java.util.Arrays.fill(key, (byte) 0); }
    }
    private static byte[] readAppKey() throws Exception {
        java.lang.Process command = new ProcessBuilder("/system/bin/run-as", PackageIdentity.APP,
                "/system/bin/cat", "files/" + HelperProtocol.KEY_FILE).redirectError(new File("/dev/null")).start();
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        Thread reader = new Thread(() -> {
            try (InputStream in = command.getInputStream()) {
                byte[] buffer = new byte[33]; int n;
                while ((n = in.read(buffer)) != -1) synchronized (data) {
                    int take = Math.min(n, 33 - data.size());
                    if (take > 0) data.write(buffer, 0, take);
                }
            } catch (Exception ignored) { }
        }, "helper-key-reader");
        reader.setDaemon(true); reader.start();
        try {
            if (!command.waitFor(3, TimeUnit.SECONDS)) { command.destroyForcibly(); throw new SecurityException(); }
            reader.join(500);
            synchronized (data) {
                if (reader.isAlive() || command.exitValue() != 0 || data.size() != HelperProtocol.KEY_SIZE) throw new SecurityException();
                return data.toByteArray();
            }
        } finally { if (command.isAlive()) command.destroyForcibly(); }
    }
}
