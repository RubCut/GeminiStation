package ru.geministation;

import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class HomeCapabilitiesTest {
    private JSONObject device() throws Exception {
        return new JSONObject("{\"id\":\"secret-device\",\"capabilities\":[" +
            "{\"type\":\"devices.capabilities.on_off\",\"state\":{\"instance\":\"on\",\"value\":true}}," +
            "{\"type\":\"devices.capabilities.toggle\",\"parameters\":{\"instance\":\"mute\"}}," +
            "{\"type\":\"devices.capabilities.range\",\"parameters\":{\"instance\":\"brightness\",\"random_access\":true,\"range\":{\"min\":10,\"max\":100,\"precision\":5}}}," +
            "{\"type\":\"devices.capabilities.range\",\"parameters\":{\"instance\":\"volume\",\"random_access\":false,\"range\":{\"min\":0,\"max\":100}}}," +
            "{\"type\":\"devices.capabilities.mode\",\"parameters\":{\"instance\":\"work_speed\",\"modes\":[{\"value\":\"low\"},{\"value\":\"high\"}]}}," +
            "{\"type\":\"devices.capabilities.color_setting\",\"parameters\":{\"color_model\":\"rgb\",\"temperature_k\":{\"min\":2700,\"max\":6500},\"color_scene\":{\"scenes\":[{\"id\":\"alarm\"}]}}}," +
            "{\"type\":\"devices.capabilities.video_stream\"}],\"properties\":[{\"type\":\"devices.properties.float\",\"state\":{\"instance\":\"temperature\",\"value\":22.5},\"parameters\":{\"unit\":\"unit.temperature.celsius\"},\"last_updated\":1710000000}]} ");
    }
    @Test public void advertisesOnlyValidatedCapabilities() throws Exception {
        JSONObject d = device(); List<HomeCapabilities.Capability> caps = HomeCapabilities.parse(d);
        assertEquals(7, caps.size());
        assertEquals(true, caps.get(0).value("true"));
        assertNull(caps.get(0).value("1"));
        assertEquals(false, caps.get(1).value("false"));
        assertEquals(15.0, ((Number)caps.get(2).value("15")).doubleValue(), 0.001);
        assertNull(caps.get(2).value("12")); assertNull(caps.get(2).value("101"));
        assertNull(caps.get(2).value("NaN")); assertNull(caps.get(2).value("1e1"));
        assertEquals("low", caps.get(3).value("low")); assertNull(caps.get(3).value("turbo"));
        assertEquals(16777215, caps.get(4).value("16777215")); assertNull(caps.get(4).value("16777216"));
        assertNotNull(caps.get(5).value("3500")); assertNull(caps.get(5).value("0"));
        assertEquals("alarm", caps.get(6).value("alarm")); assertNull(caps.get(6).value("other"));
        assertFalse(HomeCapabilities.prompt(d).toString().contains("secret-device"));
        assertEquals(22.5, HomeCapabilities.properties(d).getJSONObject(0).getDouble("value"), 0.001);
        assertEquals("unit.temperature.celsius", HomeCapabilities.properties(d).getJSONObject(0).getString("unit"));
        assertTrue(HomeCapabilities.properties(d).getJSONObject(0).getLong("updated_age_seconds") > 0);
    }
    @Test public void scopeMatchesRoomsAndFailsClosedForChangedIdentity() throws Exception {
        String kitchen = HomeSelection.key("house1", "room1"), bedroom = HomeSelection.key("house1", "room2");
        assertNotEquals(kitchen, bedroom);
        assertTrue(HomeSelection.allowed("", "account1", kitchen));
        String saved = new JSONObject().put("identity", "account1").put("rooms", new JSONArray().put(kitchen)).toString();
        assertTrue(HomeSelection.allowed(saved, "account1", kitchen));
        assertFalse(HomeSelection.allowed(saved, "account1", bedroom));
        assertFalse(HomeSelection.allowed(saved, "account2", kitchen));
        assertFalse(HomeSelection.allowed("not json", "account1", kitchen));
        JSONObject root = new JSONObject("{\"households\":[{\"id\":\"h1\",\"name\":\"Дом\"}],\"rooms\":[{\"id\":\"r1\",\"name\":\"Кухня\",\"household_id\":\"h1\"}],\"devices\":[{\"id\":\"d1\",\"name\":\"Датчик\",\"room\":\"r1\"}]}");
        SmartHome.Snapshot parsed = SmartHome.parse(root);
        assertEquals("Кухня", parsed.devices.get(0).room); assertEquals("Дом", parsed.devices.get(0).home);
        assertEquals(HomeSelection.key("h1", "r1"), parsed.devices.get(0).scope);
        assertFalse(parsed.prompt().toString().contains("d1"));
        root.getJSONArray("devices").getJSONObject(0).put("household_id", "other");
        assertEquals(0, SmartHome.parse(root).devices.size());
    }
    @Test public void homeSetParserRejectsMalformedValues() throws Exception {
        JSONObject args = new JSONObject().put("id", 0).put("capability", 2).put("value", "50");
        JSONObject call = new JSONObject().put("name", "home_set").put("args", args);
        assertEquals(DeviceToolSpec.Kind.HOME_SET, DeviceToolSpec.parse(call, 0, 1).kind);
        args.put("capability", -1); assertNull(DeviceToolSpec.parse(call, 0, 1));
        args.put("capability", 2).put("value", 50); assertNull(DeviceToolSpec.parse(call, 0, 1));
    }
}
