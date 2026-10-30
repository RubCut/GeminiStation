package ru.geministation;

import java.io.IOException;
import okhttp3.MediaType;
import okhttp3.ResponseBody;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.Test;
import static org.junit.Assert.*;

public class HttpLimitsTest {
    @Test public void limitsKnownAndChunkedBodies() throws Exception {
        assertEquals("test", HttpLimits.readUtf8(ResponseBody.create("test", MediaType.get("text/plain")), 4));
        try { HttpLimits.readUtf8(ResponseBody.create("12345", MediaType.get("text/plain")), 4); fail(); }
        catch (IOException expected) { }
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setChunkedBody("12345", 2));
            try (Response r = new OkHttpClient().newCall(new Request.Builder().url(server.url("/")).build()).execute()) {
                try { HttpLimits.readUtf8(r.body(), 4); fail(); }
                catch (IOException expected) { }
            }
        }
    }
}
