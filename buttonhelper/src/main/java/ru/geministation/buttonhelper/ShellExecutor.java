package ru.geministation.buttonhelper;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;
import ru.geministation.helperprotocol.HelperProtocol;

/** No PTY or stdin; timeout stops the direct shell, not necessarily detached descendants. */
final class ShellExecutor {
    static HelperProtocol.Result run(String shell, HelperProtocol.Request request) throws InterruptedException {
        java.lang.Process process;
        try {
            process = new ProcessBuilder(shell, "-c", request.command).redirectErrorStream(true).start();
            process.getOutputStream().close();
        } catch (Exception e) { return new HelperProtocol.Result(-1, false, false, new byte[0]); }
        Collector collector = new Collector(process.getInputStream());
        Thread reader = new Thread(collector, "helper-output"); reader.setDaemon(true); reader.start();
        boolean completed;
        try {
            completed = process.waitFor(request.timeoutSeconds, TimeUnit.SECONDS);
            if (!completed) { process.destroyForcibly(); process.waitFor(1, TimeUnit.SECONDS); }
            reader.join(1000);
        } catch (InterruptedException e) {
            process.destroyForcibly(); Thread.currentThread().interrupt(); throw e;
        } finally {
            // Do not block a worker if a detached child inherited the output pipe.
            Thread closer = new Thread(() -> { try { process.getInputStream().close(); } catch (Exception ignored) { } }, "helper-close");
            closer.setDaemon(true); closer.start();
        }
        synchronized (collector) {
            return new HelperProtocol.Result(completed ? process.exitValue() : -1, !completed,
                    collector.truncated || reader.isAlive(), collector.output.toByteArray());
        }
    }
    private static final class Collector implements Runnable {
        final InputStream input;
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        boolean truncated;
        Collector(InputStream input) { this.input = input; }
        @Override public void run() {
            try {
                byte[] buffer = new byte[4096]; int n;
                while ((n = input.read(buffer)) != -1) synchronized (this) {
                    int take = Math.min(n, HelperProtocol.MAX_OUTPUT - output.size());
                    if (take > 0) output.write(buffer, 0, take);
                    if (take < n) truncated = true;
                }
            } catch (Exception ignored) { synchronized (this) { truncated = true; } }
        }
    }
    private ShellExecutor() { }
}
