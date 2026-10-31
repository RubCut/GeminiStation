package ru.geministation;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class VoiceTuningTest {
    @Test public void defaultsRoundTripAndWakeChangeDetection() throws Exception {
        VoiceTuning d = VoiceTuning.defaults(), result = VoiceTuning.parse(d.json());
        assertTrue(d.valid()); assertTrue(d.preconnect); assertTrue(d.earlyCapture);
        assertEquals(200, result.wakeMinMs); assertEquals(5000, result.silenceMs);
        assertTrue(d.sameWake(result));
        assertFalse(d.sameWake(new VoiceTuning(300, 400, 1200, 5000, 800, 1800, true, true)));
    }
    @Test public void rejectsOutOfRangeAndMalformedSettings() throws Exception {
        JSONObject data = VoiceTuning.defaults().json();
        data.put("wakeMinMs", 99);
        try { VoiceTuning.parse(data); fail(); } catch (IllegalArgumentException expected) { }
        data = VoiceTuning.defaults().json(); data.put("silenceMs", "5000");
        try { VoiceTuning.parse(data); fail(); } catch (IllegalArgumentException expected) { }
        data = VoiceTuning.defaults().json(); data.put("earlyCapture", 1);
        try { VoiceTuning.parse(data); fail(); } catch (IllegalArgumentException expected) { }
    }
}
