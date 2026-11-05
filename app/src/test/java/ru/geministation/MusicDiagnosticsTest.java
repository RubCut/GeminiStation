package ru.geministation;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class MusicDiagnosticsTest {
    @Test public void reportsOnlyMatchedResponseAndFixedStatuses() throws Exception {
        JSONObject response = new JSONObject().put("requestId", "our-id").put("status", "ok")
                .put("token", "private-token").put("playerState", new JSONObject().put("title", "private-title"));
        assertNull(MusicDiagnostics.status(response, "different-id"));
        assertEquals("ok", MusicDiagnostics.status(response, "our-id"));
        response.put("status", "private-token");
        assertEquals("не указан", MusicDiagnostics.status(response, "our-id"));
        response.put("errorText", "private-token");
        assertEquals("error", MusicDiagnostics.status(response, "our-id"));
        response.put("status", "ok");
        assertEquals("error", MusicDiagnostics.status(response, "our-id"));
        assertNull(MusicDiagnostics.status(response, ""));
        assertEquals("success", MusicDiagnostics.status(new JSONObject().put("id", "our-id").put("status", "SUCCESS"), "our-id"));
    }
}
