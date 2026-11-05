package ru.geministation;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.Test;
import static org.junit.Assert.*;

public class KeyRotationTest {
    private List<KeyRotation.Entry> keys() {
        return Arrays.asList(new KeyRotation.Entry("a", "secret-a"), new KeyRotation.Entry("b", "secret-b"), new KeyRotation.Entry("c", "secret-c"));
    }
    @Test public void rotatesOn429AndRemembersWorkingKey() throws Exception {
        KeyRotation rotation = new KeyRotation(() -> {});
        List<String> visited = new ArrayList<>();
        String result = rotation.run(keys(), () -> true, key -> {
            visited.add(key);
            if (key.equals("secret-a")) throw new KeyRotation.RateLimit("Gemini API");
            return "ok";
        });
        assertEquals("ok", result);
        assertEquals(Arrays.asList("secret-a", "secret-b"), visited);
        assertEquals("secret-b", rotation.attempt(keys()).key());
    }
    @Test public void boundedWhenAllKeysExhaustedAndNoFallbackOnOtherErrors() throws Exception {
        KeyRotation rotation = new KeyRotation(() -> {});
        List<String> visited = new ArrayList<>();
        try {
            rotation.run(keys(), () -> true, key -> { visited.add(key); throw new KeyRotation.RateLimit("Gemini API"); });
            fail();
        } catch (KeyRotation.RateLimit expected) { assertEquals(3, visited.size()); }
        visited.clear();
        try {
            rotation.run(keys(), () -> true, key -> { visited.add(key); throw new IOException("HTTP 403"); });
            fail();
        } catch (IOException expected) { assertEquals(1, visited.size()); }
    }
    @Test public void doesNotReplayPartialAudioOrRetryAfterCancellation() throws Exception {
        KeyRotation rotation = new KeyRotation(() -> {});
        List<String> visited = new ArrayList<>();
        try {
            rotation.run(keys(), () -> true, key -> { visited.add(key); throw new KeyRotation.RateLimit("Gemini TTS", false); });
            fail();
        } catch (KeyRotation.RateLimit expected) { assertEquals(1, visited.size()); }
        assertEquals("secret-b", rotation.attempt(keys()).key());
        AtomicBoolean active = new AtomicBoolean(true);
        visited.clear();
        try {
            rotation.run(keys(), active::get, key -> { visited.add(key); active.set(false); throw new KeyRotation.RateLimit("Gemini API"); });
            fail();
        } catch (IOException expected) { assertEquals(1, visited.size()); }
    }
    @Test public void emptyAndRemovedKeysAreHandled() throws Exception {
        KeyRotation rotation = new KeyRotation(() -> {});
        try { rotation.run(Collections.emptyList(), () -> true, key -> "bad"); fail(); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("API-ключ")); }
        KeyRotation.Attempt attempt = rotation.attempt(keys());
        assertTrue(attempt.advance());
        assertEquals("secret-c", rotation.attempt(Collections.singletonList(keys().get(2))).key());
    }
    @Test public void actualHttp429FallsBackToNextHeader() throws Exception {
        MockWebServer server = new MockWebServer();
        server.enqueue(new MockResponse().setResponseCode(429));
        server.enqueue(new MockResponse().setResponseCode(200).setBody("ok"));
        server.start();
        OkHttpClient client = new OkHttpClient();
        try {
            KeyRotation rotation = new KeyRotation(() -> {});
            String result = rotation.run(keys(), () -> true, key -> {
                Request request = new Request.Builder().url(server.url("/test")).header("x-goog-api-key", key).build();
                try (Response response = client.newCall(request).execute()) {
                    if (response.code() == 429) throw new KeyRotation.RateLimit("Gemini API");
                    if (!response.isSuccessful()) throw new IOException("HTTP " + response.code());
                    return response.body().string();
                }
            });
            assertEquals("ok", result);
            assertEquals("secret-a", server.takeRequest().getHeader("x-goog-api-key"));
            assertEquals("secret-b", server.takeRequest().getHeader("x-goog-api-key"));
        } finally {
            server.shutdown();
            client.dispatcher().executorService().shutdown();
            client.connectionPool().evictAll();
        }
    }
}
