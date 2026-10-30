package ru.geministation;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class SmartHomeTest {
    @Test public void filtersAndMapsDevicesWithoutExposingTheirIds() throws Exception {
        JSONObject info = new JSONObject("{\"devices\":[{\"id\":\"secret-id\",\"name\":\"Лампа\",\"room\":\"Кухня\",\"capabilities\":[{\"type\":\"devices.capabilities.on_off\"}]},{\"id\":\"other\",\"name\":\"Датчик\",\"capabilities\":[]}]}");
        SmartHome.Snapshot snapshot = SmartHome.parse(info);
        assertEquals(1, snapshot.devices.size());
        assertEquals(0, snapshot.prompt().getJSONObject(0).getInt("id"));
        assertFalse(snapshot.prompt().toString().contains("secret-id"));
        JSONObject body = SmartHome.actionBody(snapshot.devices.get(0), true);
        JSONObject action = body.getJSONArray("devices").getJSONObject(0).getJSONArray("actions").getJSONObject(0);
        assertEquals("secret-id", body.getJSONArray("devices").getJSONObject(0).getString("id"));
        assertTrue(action.getJSONObject("state").getBoolean("value"));
    }
    @Test public void onlyExactDoneCountsAsSuccessful() throws Exception {
        SmartHome.Device d = new SmartHome.Device("one", "Лампа", "");
        JSONObject response = new JSONObject("{\"status\":\"ok\",\"devices\":[{\"id\":\"one\",\"capabilities\":[{\"type\":\"devices.capabilities.on_off\",\"state\":{\"instance\":\"on\",\"action_result\":{\"status\":\"DONE\"}}}]}]}");
        assertTrue(SmartHome.accepted(response, d));
        assertFalse(SmartHome.accepted(response, new SmartHome.Device("two", "", "")));
        response.getJSONArray("devices").getJSONObject(0).getJSONArray("capabilities").getJSONObject(0).getJSONObject("state").getJSONObject("action_result").put("status", "ERROR");
        assertFalse(SmartHome.accepted(response, d));
        assertFalse(SmartHome.accepted(new JSONObject().put("status", "ok"), d));
    }
    @Test public void callsRequireValidIndexAndBoolean() throws Exception {
        JSONObject call = new JSONObject().put("name", "home_control").put("args", new JSONObject().put("id", 0).put("on", true));
        assertEquals(DeviceToolSpec.Kind.HOME_CONTROL, DeviceToolSpec.parse(call, 0, 1).kind);
        assertNull(DeviceToolSpec.parse(call, 0, 0));
        call.getJSONObject("args").put("on", "true");
        assertNull(DeviceToolSpec.parse(call, 0, 1));
        call.getJSONObject("args").put("on", true).put("id", 0.5);
        assertNull(DeviceToolSpec.parse(call, 0, 1));
    }
    @Test public void musicSearchUsesOnlyRealAvailableNumericTrack() throws Exception {
        JSONObject result = new JSONObject("{\"tracks\":{\"results\":[{\"id\":\"42\",\"title\":\"Недоступный\",\"available\":false},{\"id\":\"123\",\"title\":\"Песня\",\"artists\":[{\"name\":\"Исполнитель\"}]}]}}");
        YandexCloud.Track track = YandexCloud.parseSearch(result);
        assertEquals("123", track.id);
        assertEquals("Исполнитель — Песня", track.title);
        JSONObject call = new JSONObject().put("name", "yandex_music").put("args", new JSONObject().put("query", "Песня"));
        assertEquals(DeviceToolSpec.Kind.YANDEX_MUSIC, DeviceToolSpec.parse(call, 0).kind);
        call.getJSONObject("args").put("query", " ");
        assertNull(DeviceToolSpec.parse(call, 0));
    }
}
