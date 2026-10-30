package ru.geministation;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class MediaSeekPolicyTest {
    private JSONObject call(String mode, Object seconds) throws Exception {
        return new JSONObject().put("name", "media_seek").put("args", new JSONObject().put("mode", mode).put("seconds", seconds));
    }
    @Test public void absoluteAndRelativeTargets() {
        assertEquals(150000, MediaSeekPolicy.target("absolute", 150, -1, 200000));
        assertEquals(-1, MediaSeekPolicy.target("absolute", 201, 0, 200000));
        assertEquals(0, MediaSeekPolicy.target("absolute", 0, -1, -1));
        assertEquals(125000, MediaSeekPolicy.target("forward", 30, 95000, 200000));
        assertEquals(200000, MediaSeekPolicy.target("forward", 120, 95000, 200000));
        assertEquals(0, MediaSeekPolicy.target("backward", 120, 95000, 200000));
        assertEquals(-1, MediaSeekPolicy.target("forward", 1, -1, -1));
        assertEquals(-1, MediaSeekPolicy.target("forward", 1, Long.MAX_VALUE, -1));
    }
    @Test public void strictToolValidation() throws Exception {
        assertEquals(DeviceToolSpec.Kind.MEDIA_SEEK, DeviceToolSpec.parse(call("absolute", 150), 0).kind);
        assertEquals("backward", DeviceToolSpec.parse(call("backward", 30), 0).data);
        assertNull(DeviceToolSpec.parse(call("sideways", 5), 0));
        assertNull(DeviceToolSpec.parse(call("forward", 0), 0));
        assertNull(DeviceToolSpec.parse(call("absolute", -1), 0));
        assertNull(DeviceToolSpec.parse(call("absolute", 604801), 0));
        assertNull(DeviceToolSpec.parse(call("absolute", "150"), 0));
        assertNull(DeviceToolSpec.parse(call("absolute", 1.5), 0));
    }
}
