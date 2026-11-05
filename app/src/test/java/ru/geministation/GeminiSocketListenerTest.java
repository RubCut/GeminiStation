package ru.geministation;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okio.ByteString;
import org.junit.Test;
import static org.junit.Assert.*;

public class GeminiSocketListenerTest {
    @Test public void liveSetupAndTranscriptReachSameParserFromRealBinaryAndTextFrames() throws Exception {
        final String setup = "{\"setupComplete\":{}}";
        final String partial = "{\"serverContent\":{\"interimInputTranscription\":{\"text\":\"Привет\"}}}";
        MockWebServer server = new MockWebServer();
        server.enqueue(new MockResponse().withWebSocketUpgrade(new WebSocketListener() {
            @Override public void onOpen(WebSocket socket, Response response) {
                socket.send(ByteString.encodeUtf8(setup));
                socket.send(partial);
            }
        }));
        server.start();
        OkHttpClient client = new OkHttpClient();
        CountDownLatch frames = new CountDownLatch(2);
        List<String> received = new CopyOnWriteArrayList<>();
        List<String> invalid = new CopyOnWriteArrayList<>();
        GeminiSocketListener listener = new GeminiSocketListener() {
            @Override protected void onJsonFrame(WebSocket socket, String message) { received.add(message); frames.countDown(); }
            @Override protected void onInvalidFrame(String reason) { invalid.add(reason); }
        };
        WebSocket socket = client.newWebSocket(new Request.Builder().url(server.url("/live")).build(), listener);
        try {
            assertTrue("binary setup and text transcript should arrive", frames.await(5, TimeUnit.SECONDS));
            assertEquals(2, received.size());
            assertEquals(setup, received.get(0));
            assertEquals(partial, received.get(1));
            assertTrue(invalid.isEmpty());
        } finally {
            socket.cancel();
            server.shutdown();
            client.dispatcher().executorService().shutdown();
            client.connectionPool().evictAll();
        }
    }
    @Test public void rejectsInvalidUtf8AndOversizedBinaryFrames() {
        List<String> invalid = new CopyOnWriteArrayList<>();
        GeminiSocketListener listener = new GeminiSocketListener() {
            @Override protected void onJsonFrame(WebSocket socket, String message) { fail("should not reach parser"); }
            @Override protected void onInvalidFrame(String reason) { invalid.add(reason); }
        };
        listener.onMessage(null, ByteString.of(new byte[]{(byte) 0xc3, 0x28}));
        listener.onMessage(null, ByteString.of(new byte[1024 * 1024 + 1]));
        assertEquals(2, invalid.size());
        assertEquals("invalid UTF-8 frame", invalid.get(0));
        assertEquals("oversized binary frame", invalid.get(1));
    }
}
