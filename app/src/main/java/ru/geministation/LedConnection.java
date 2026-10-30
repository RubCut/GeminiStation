package ru.geministation;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/** One reusable local WebSocket. A sent command does not wait for an optional ACK. */
final class LedConnection {
    private final Object lock = new Object();
    private final OkHttpClient client;
    private final String url;
    private final Predicate<String> isReady;
    private final Predicate<String> isError;
    private WebSocket socket;
    private boolean ready;
    private CountDownLatch connecting;

    LedConnection(OkHttpClient client, String url, Predicate<String> isReady, Predicate<String> isError) {
        this.client = client; this.url = url; this.isReady = isReady; this.isError = isError;
    }
    boolean connect(String hello, long timeoutMs) throws InterruptedException {
        CountDownLatch gate;
        synchronized (lock) {
            if (ready && socket != null) return true;
            if (connecting == null) {
                ready = false;
                gate = new CountDownLatch(1);
                connecting = gate;
                socket = client.newWebSocket(new Request.Builder().url(url).build(), new WebSocketListener() {
                    @Override public void onOpen(WebSocket ws, Response response) {
                        synchronized (lock) { if (socket == ws && !ws.send(hello)) failLocked(ws); }
                    }
                    @Override public void onMessage(WebSocket ws, String message) {
                        synchronized (lock) {
                            if (socket != ws) return;
                            try {
                                if (isError.test(message)) { failLocked(ws); return; }
                                if (!ready && isReady.test(message)) {
                                    ready = true;
                                    if (connecting != null) connecting.countDown();
                                    connecting = null;
                                }
                            } catch (RuntimeException ignored) { }
                        }
                    }
                    @Override public void onFailure(WebSocket ws, Throwable failure, Response response) {
                        synchronized (lock) { failLocked(ws); }
                    }
                    @Override public void onClosing(WebSocket ws, int code, String reason) {
                        synchronized (lock) { failLocked(ws); }
                    }
                    @Override public void onClosed(WebSocket ws, int code, String reason) {
                        synchronized (lock) { failLocked(ws); }
                    }
                });
            } else gate = connecting;
        }
        if (!gate.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            synchronized (lock) { if (!ready && connecting == gate) closeLocked(); }
        }
        synchronized (lock) { return ready && socket != null; }
    }
    boolean send(String frame) {
        synchronized (lock) { return ready && socket != null && socket.send(frame); }
    }
    void close() { synchronized (lock) { closeLocked(); } }
    private void failLocked(WebSocket ws) {
        if (socket == ws) closeLocked();
    }
    private void closeLocked() {
        if (socket != null) socket.cancel();
        socket = null; ready = false;
        if (connecting != null) connecting.countDown();
        connecting = null;
    }
}
