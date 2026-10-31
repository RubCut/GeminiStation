package ru.geministation;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class HomeDiagnosticsTest {
    @Test public void errorCodesAreAllowlisted() {
        assertEquals("DEVICE_UNREACHABLE", HomeDiagnostics.errorCode("DEVICE_UNREACHABLE"));
        assertEquals("неизвестный код", HomeDiagnostics.errorCode("y0_sensitive_token"));
        assertEquals("без кода", HomeDiagnostics.errorCode(""));
    }
    @Test public void outputNeverContainsUntrustedResponseFields() throws Exception {
        JSONObject root = new JSONObject("{\"status\":\"ok\",\"devices\":[{\"id\":\"device-secret\",\"capabilities\":[{\"type\":\"devices.capabilities.on_off\",\"state\":{\"instance\":\"on\",\"action_result\":{\"status\":\"ERROR\",\"error_code\":\"DEVICE_UNREACHABLE\",\"error_message\":\"private-secret\"}}}]}]}");
        assertEquals("ERROR: DEVICE_UNREACHABLE", HomeDiagnostics.outcome(root, "device-secret"));
        assertEquals("нет результата выбранного устройства", HomeDiagnostics.outcome(root, "other"));
        JSONObject result = root.getJSONArray("devices").getJSONObject(0).getJSONArray("capabilities").getJSONObject(0).getJSONObject("state").getJSONObject("action_result");
        result.put("error_code", "PRIVATE_SECRET");
        assertEquals("ERROR: неизвестный код", HomeDiagnostics.outcome(root, "device-secret"));
        result.put("status", "DONE");
        assertEquals("DONE", HomeDiagnostics.outcome(root, "device-secret"));
    }
}
