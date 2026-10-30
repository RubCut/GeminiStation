package ru.geministation;

import org.junit.Test;
import org.json.JSONObject;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import static org.junit.Assert.*;

public class RateLimitSignalsTest {
    @Test public void explicitQuotaErrorsOnly() {
        assertTrue(RateLimitSignals.apiError(429, ""));
        assertTrue(RateLimitSignals.apiError(8, "RESOURCE_EXHAUSTED"));
        assertTrue(RateLimitSignals.closeReason("429 RESOURCE_EXHAUSTED"));
        assertFalse(RateLimitSignals.apiError(403, "PERMISSION_DENIED"));
        assertFalse(RateLimitSignals.apiError(500, "INTERNAL"));
        assertFalse(RateLimitSignals.closeReason("status 1429"));
        assertFalse(RateLimitSignals.closeReason(null));
    }
    @Test public void explicitlyReportedResourceExhaustionOnly() throws Exception {
        assertTrue(RateLimitSignals.apiPayload(new JSONObject("{\"error\":{\"code\":403,\"status\":\"RESOURCE_EXHAUSTED\"}}")));
        assertFalse(RateLimitSignals.apiPayload(new JSONObject("{\"error\":{\"code\":403,\"status\":\"PERMISSION_DENIED\"}}")));
        assertFalse(RateLimitSignals.apiPayload(new JSONObject("{\"status\":\"RESOURCE_EXHAUSTED\"}")));
        MockWebServer server = new MockWebServer();
        server.enqueue(new MockResponse().setResponseCode(429));
        server.enqueue(new MockResponse().setResponseCode(403).setBody("{\"error\":{\"status\":\"RESOURCE_EXHAUSTED\"}}"));
        server.enqueue(new MockResponse().setResponseCode(403).setBody("{\"error\":{\"status\":\"PERMISSION_DENIED\"}}"));
        server.enqueue(new MockResponse().setResponseCode(500).setBody("not-json"));
        server.start();
        OkHttpClient client = new OkHttpClient();
        try {
            for (boolean expected : new boolean[]{true, true, false, false}) {
                try (Response response = client.newCall(new Request.Builder().url(server.url("/")).build()).execute()) {
                    assertEquals(expected, RateLimitSignals.httpResponse(response));
                }
            }
        } finally {
            server.shutdown(); client.dispatcher().executorService().shutdown(); client.connectionPool().evictAll();
        }
    }
}
