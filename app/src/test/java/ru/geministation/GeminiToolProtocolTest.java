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
    @Test public void customPromptReplacesDefaultButKeepsLiveAppList() throws Exception {
        String prompt = GeminiToolProtocol.request(new JSONArray(), new JSONArray().put("APP"), "Отвечай стихами")
                .getJSONObject("systemInstruction").getJSONArray("parts").getJSONObject(0).getString("text");
        assertTrue(prompt.startsWith("Отвечай стихами"));
        assertTrue(prompt.contains("APP"));
        assertFalse(prompt.contains("На станции громкость"));
        assertTrue(SystemPrompt.DEFAULT.contains("3 — это 30"));
        assertTrue(SystemPrompt.DEFAULT.contains("6 с половиной — 65"));
        assertTrue(SystemPrompt.DEFAULT.contains("Явно указанные проценты"));
        assertTrue(SystemPrompt.valid(""));
        assertFalse(SystemPrompt.valid(new String(new char[8001])));
    }
    @Test public void textAndToolsCarryOptionalDialogDecisions() throws Exception {
        JSONObject request = GeminiToolProtocol.request(new JSONArray(), new JSONArray(), "Коротко");
        String instruction = request.getJSONObject("systemInstruction").getJSONArray("parts").getJSONObject(0).getString("text");
        assertTrue(instruction.contains("[[DIALOG_END]]"));
        JSONArray defs = request.getJSONArray("tools").getJSONObject(0).getJSONArray("functionDeclarations");
        for (int i = 0; i < defs.length(); i++)
            assertTrue(defs.getJSONObject(i).getJSONObject("parameters").getJSONObject("properties").has("dialog"));
        GeminiToolProtocol.Result text = GeminiToolProtocol.parse(reply(new JSONArray().put(new JSONObject().put("text", "Пока! [[DIALOG_END]]"))));
        assertEquals("Пока!", text.text); assertTrue(text.finishDialog);
        JSONObject call = new JSONObject().put("name", "media_control").put("args", new JSONObject().put("action", "pause").put("dialog", "end"));
        GeminiToolProtocol.Result tool = GeminiToolProtocol.parse(reply(new JSONArray().put(new JSONObject().put("functionCall", call))));
        assertTrue(tool.finishDialog);
        assertEquals(DeviceToolSpec.Kind.MEDIA_CONTROL, DeviceToolSpec.parse(tool.call, 0).kind);
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
