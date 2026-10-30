package ru.geministation;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class DeviceToolSpecTest {
    private JSONObject call(String name, JSONObject args) throws Exception { return new JSONObject().put("name", name).put("args", args); }
    @Test public void acceptsOnlyBoundedApplicationIdsAndVolume() throws Exception {
        assertEquals(DeviceToolSpec.Kind.OPEN_APP, DeviceToolSpec.parse(call("open_app", new JSONObject().put("id", 0)), 1).kind);
        assertNull(DeviceToolSpec.parse(call("open_app", new JSONObject().put("id", 1)), 1));
        assertNull(DeviceToolSpec.parse(call("open_app", new JSONObject().put("id", -1)), 1));
        assertNull(DeviceToolSpec.parse(call("open_app", new JSONObject().put("id", "0")), 1));
        assertNull(DeviceToolSpec.parse(call("open_app", new JSONObject().put("id", 0.5)), 1));
        assertEquals(100, DeviceToolSpec.parse(call("set_volume", new JSONObject().put("percent", 100)), 1).number);
        assertNull(DeviceToolSpec.parse(call("set_volume", new JSONObject().put("percent", 101)), 1));
        assertNull(DeviceToolSpec.parse(call("set_volume", new JSONObject().put("percent", -1)), 1));
        assertNull(DeviceToolSpec.parse(call("shell", new JSONObject().put("command", "anything")), 1));
    }
    @Test public void linksAndSearchAreRestricted() throws Exception {
        assertEquals(DeviceToolSpec.Kind.OPEN_LINK, DeviceToolSpec.parse(call("open_link", new JSONObject().put("id", 0).put("url", "https://youtu.be/abcdefghijk")), 1).kind);
        assertNull(DeviceToolSpec.parse(call("open_link", new JSONObject().put("id", 0).put("url", "intent://attack")), 1));
        assertNull(DeviceToolSpec.parse(call("open_link", new JSONObject().put("id", 1).put("url", "https://example.org")), 1));
        assertEquals(DeviceToolSpec.Kind.SEARCH_YOUTUBE, DeviceToolSpec.parse(call("search_youtube", new JSONObject().put("id", 0).put("query", "котики")), 1).kind);
        assertNull(DeviceToolSpec.parse(call("search_youtube", new JSONObject().put("id", 0).put("query", " ")), 1));
    }
    @Test public void onlyAccessibleLauncherEntriesAreEligible() {
        assertTrue(DeviceToolSpec.eligible(true,true,true,true,true,false));
        assertFalse(DeviceToolSpec.eligible(false,true,true,true,true,false));
        assertFalse(DeviceToolSpec.eligible(true,false,true,true,true,false));
        assertFalse(DeviceToolSpec.eligible(true,true,false,true,true,false));
        assertFalse(DeviceToolSpec.eligible(true,true,true,false,true,false));
        assertFalse(DeviceToolSpec.eligible(true,true,true,true,false,false));
        assertFalse(DeviceToolSpec.eligible(true,true,true,true,true,true));
    }
}
