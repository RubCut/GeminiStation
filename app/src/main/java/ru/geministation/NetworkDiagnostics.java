package ru.geministation;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.Call;
import okhttp3.Connection;
import okhttp3.EventListener;
import okhttp3.Handshake;
import okhttp3.Response;

/** Only timings, HTTP codes and exception classes; no headers, URLs, keys or bodies. */
final class NetworkDiagnostics extends EventListener {
    private static final AtomicInteger NEXT = new AtomicInteger();
    private final int id = NEXT.incrementAndGet();
    private final String type;
    private final Runnable invalidateDns;
    private long start, dnsStart, connectStart, tlsStart;
    private boolean newConnection;
    NetworkDiagnostics(String type, Runnable invalidateDns) { this.type = type; this.invalidateDns = invalidateDns; }
    private String label() { return type + " #" + id + ": "; }
    private long elapsed(long since) { return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - since); }
    @Override public void callStart(Call call) { start = System.nanoTime(); AppLog.add(label() + "запрос начат"); }
    @Override public void dnsStart(Call call, String domainName) { dnsStart = System.nanoTime(); }
    @Override public void dnsEnd(Call call, String domainName, List<InetAddress> addresses) {
        AppLog.add(label() + "DNS " + elapsed(dnsStart) + " мс; адресов " + addresses.size());
    }
    @Override public void connectStart(Call call, InetSocketAddress address, Proxy proxy) { newConnection = true; connectStart = System.nanoTime(); }
    @Override public void secureConnectStart(Call call) { tlsStart = System.nanoTime(); }
    @Override public void secureConnectEnd(Call call, Handshake handshake) { AppLog.add(label() + "TLS " + elapsed(tlsStart) + " мс"); }
    @Override public void connectEnd(Call call, InetSocketAddress address, Proxy proxy, okhttp3.Protocol protocol) {
        AppLog.add(label() + "соединение " + elapsed(connectStart) + " мс");
    }
    @Override public void connectFailed(Call call, InetSocketAddress address, Proxy proxy, okhttp3.Protocol protocol, IOException e) {
        if (invalidateDns != null) invalidateDns.run();
        AppLog.add(label() + "ошибка соединения " + e.getClass().getSimpleName());
    }
    @Override public void connectionAcquired(Call call, Connection connection) {
        if (!newConnection) AppLog.add(label() + "повторное использование соединения");
    }
    @Override public void responseHeadersEnd(Call call, Response response) {
        AppLog.add(label() + "HTTP " + response.code() + ", первый ответ через " + elapsed(start) + " мс");
    }
    @Override public void callEnd(Call call) { AppLog.add(label() + "получено за " + elapsed(start) + " мс"); }
    @Override public void callFailed(Call call, IOException e) { AppLog.add(label() + "ошибка " + e.getClass().getSimpleName() + " через " + elapsed(start) + " мс"); }
}
