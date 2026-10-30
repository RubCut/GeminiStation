package ru.geministation.buttonhelper;

import android.os.Process;
import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Comparator;

/** Runs only when explicitly started by ADB shell, not as an Android app service. */
public final class ShellButtonMonitor {
    private static final String REMOTE_NAME = "Yandex Remote 2";
    private static final String LOCK = "/data/local/tmp/gemini-button.lock";
    private static final long RETRY_MS = 2000;

    public static void main(String[] args) {
        try (RandomAccessFile lockFile = new RandomAccessFile(LOCK, "rw");
             FileLock lock = lockFile.getChannel().tryLock()) {
            if (lock == null) {
                System.err.println("Gemini button helper is already running.");
                return;
            }
            System.out.println("Gemini button helper started; pid=" + Process.myPid());
            monitor();
        } catch (Exception e) {
            System.err.println("Gemini button helper stopped: " + e);
        }
    }

    private static void monitor() throws InterruptedException {
        boolean is64Bit = Process.is64Bit();
        byte[] event = new byte[InputEvent.size(is64Bit)];
        boolean waiting = false;
        while (true) {
            File device = findRemote();
            if (device == null) {
                if (!waiting) System.out.println("Waiting for Yandex Remote 2");
                waiting = true;
                Thread.sleep(RETRY_MS);
                continue;
            }
            waiting = false;
            System.out.println("Listening on " + device);
            try (DataInputStream input = new DataInputStream(new FileInputStream(device))) {
                while (true) {
                    input.readFully(event);
                    if (InputEvent.isSearchDown(event, is64Bit)) startAssistant();
                }
            } catch (IOException e) {
                System.err.println("Remote unavailable: " + e);
                Thread.sleep(RETRY_MS);
            }
        }
    }

    private static File findRemote() {
        File[] devices = new File("/sys/class/input").listFiles((dir, name) -> name.matches("event[0-9]+"));
        if (devices == null) return null;
        Arrays.sort(devices, Comparator.comparing(File::getName));
        for (File sysDevice : devices) {
            File nameFile = new File(sysDevice, "device/name");
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    new FileInputStream(nameFile), StandardCharsets.UTF_8))) {
                if (REMOTE_NAME.equals(reader.readLine())) {
                    return new File("/dev/input", sysDevice.getName());
                }
            } catch (IOException ignored) { /* device can disappear while scanning */ }
        }
        return null;
    }

    private static void startAssistant() {
        try {
            java.lang.Process command = new ProcessBuilder("/system/bin/am", "start", "-a",
                    "android.intent.action.ASSIST", "-n", "ru.geministation/.MainActivity")
                    .redirectErrorStream(true).start();
            // Drain output before waitFor, so a full pipe cannot stall the input monitor.
            try (BufferedReader output = new BufferedReader(new InputStreamReader(
                    command.getInputStream(), StandardCharsets.UTF_8))) {
                while (output.readLine() != null) { /* no input events or personal data logged */ }
            }
            int status = command.waitFor();
            System.out.println("Assistant launch exit=" + status);
        } catch (IOException e) {
            System.err.println("Assistant launch failed: " + e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
    private ShellButtonMonitor() { }
}
