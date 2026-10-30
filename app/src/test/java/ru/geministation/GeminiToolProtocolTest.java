package ru.geministation;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class GeminiToolProtocolTest {
    private JSONObject reply(JSONArray parts) throws Exception {
        return new JSONObject().put("candidates", new JSONArray().put(new JSONObject().put("content", new JSONObject().put("parts", parts))));
    }
    @Test public void requestIncludesIdNameAndLinkHintsInSystemInstruction() throws Exception {
        JSONArray apps = new JSONArray().put(new JSONObject().put("id", 0).put("name", "SmartTube").put("links", new JSONArray().put("https://youtube.com")));
        JSONObject request = GeminiToolProtocol.request(new JSONArray(), apps);
        String prompt = request.getJSONObject("systemInstruction").getJSONArray("parts").getJSONObject(0).getString("text");
        assertTrue(prompt.contains("SmartTube"));
        assertTrue(prompt.contains("youtube.com"));
        assertTrue(prompt.contains("\"id\":0"));
        assertTrue(request.getJSONArray("tools").getJSONObject(0).getJSONArray("functionDeclarations").length() >= 8);
    }
    @Test public void parsesTextOrOneFunctionAndRejectsMultipleActions() throws Exception {
        assertEquals("Привет", GeminiToolProtocol.parse(reply(new JSONArray().put(new JSONObject().put("text", "Привет")))).text);
        JSONObject call = new JSONObject().put("name", "go_home").put("args", new JSONObject());
        GeminiToolProtocol.Result tool = GeminiToolProtocol.parse(reply(new JSONArray().put(new JSONObject().put("functionCall", call).put("thoughtSignature", "opaque"))));
        assertEquals("go_home", tool.call.getString("name"));
        GeminiToolProtocol.Result multiple = GeminiToolProtocol.parse(reply(new JSONArray().put(new JSONObject().put("functionCall", call)).put(new JSONObject().put("functionCall", call))));
        assertNull(multiple.call);
        assertTrue(multiple.text.contains("одно действие"));
    }
}
