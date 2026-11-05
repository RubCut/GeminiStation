package ru.geministation;

import org.json.JSONObject;
import org.junit.Test;
import java.util.HashSet;
import static org.junit.Assert.*;

public class TtsVoicesTest {
    @Test public void catalogHasDefaultAndOnlyDistinctValidNames() {
        assertEquals(30, TtsVoices.ALL.size());
        assertEquals(30, new HashSet<>(TtsVoices.ALL).size());
        assertEquals("Kore", TtsVoices.orDefault(null));
        assertEquals("Kore", TtsVoices.orDefault("invalid"));
        assertFalse(TtsVoices.valid("kore"));
        for (String voice : TtsVoices.ALL) {
            assertTrue(TtsVoices.valid(voice));
            assertEquals(voice, TtsVoices.orDefault(voice));
        }
    }
    @Test public void speechRequestPinsEveryVoiceAndPreservesText() throws Exception {
        for (String voice : TtsVoices.ALL) {
            JSONObject request = GeminiClient.speechRequest("Привет!", voice);
            assertEquals("Привет!", request.getJSONArray("contents").getJSONObject(0)
                    .getJSONArray("parts").getJSONObject(0).getString("text"));
            JSONObject generation = request.getJSONObject("generationConfig");
            assertEquals("AUDIO", generation.getJSONArray("responseModalities").getString(0));
            assertEquals(voice, generation.getJSONObject("speechConfig")
                    .getJSONObject("voiceConfig").getString("voice"));
        }
    }
    @Test(expected = IllegalArgumentException.class) public void invalidVoiceCannotReachTtsRequest() throws Exception {
        GeminiClient.speechRequest("Hello", "not-a-voice");
    }
}
