package ru.geministation;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.Test;
import static org.junit.Assert.*;

public class LedConnectionTest {
    @Test public void reusesSocketAndSendsWithoutWaitingForAck() throws Exception {
        BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().withWebSocketUpgrade(new WebSocketListener() {
                @Override public void onMessage(WebSocket ws, String message) {
                    messages.add(message);
                    if ("hello".equals(message)) ws.send("ready");
                    // No ACK for display commands: several firmware versions omit it.
                }
            }));
            server.start();
            OkHttpClient client = new OkHttpClient();
            LedConnection connection = new LedConnection(client, server.url("/").toString().replace("http:", "ws:"), "ready"::equals, "error"::equals);
            assertTrue(connection.connect("hello", 3000));
            assertEquals("hello", messages.poll(3, TimeUnit.SECONDS));
            assertTrue(connection.send("listening"));
            assertTrue(connection.connect("another hello", 3000));
            assertTrue(connection.send("speaking"));
            assertTrue(connection.send("clear"));
            assertEquals("listening", messages.poll(3, TimeUnit.SECONDS));
            assertEquals("speaking", messages.poll(3, TimeUnit.SECONDS));
            assertEquals("clear", messages.poll(3, TimeUnit.SECONDS));
            assertEquals(1, server.getRequestCount());
            connection.close();
            assertFalse(connection.send("late"));
            client.dispatcher().executorService().shutdownNow();
        }
    }
    @Test public void rejectsHandshakeError() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().withWebSocketUpgrade(new WebSocketListener() {
                @Override public void onMessage(WebSocket ws, String message) { ws.send("error"); }
            }));
            server.start(); OkHttpClient client = new OkHttpClient();
            LedConnection connection = new LedConnection(client, server.url("/").toString().replace("http:", "ws:"), "ready"::equals, "error"::equals);
            assertFalse(connection.connect("hello", 3000));
            assertFalse(connection.send("clear"));
            connection.close(); client.dispatcher().executorService().shutdownNow();
        }
    }
}
