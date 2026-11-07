package ru.geministation;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class ApiConfigPolicyTest {
    @Test public void oldAllKeyDeleteAndNonBooleansAreRejected() throws Exception {
        assertTrue(ApiConfigPolicy.acceptsLegacyDeleteFlag(new JSONObject()));
        assertTrue(ApiConfigPolicy.acceptsLegacyDeleteFlag(new JSONObject().put("deleteKey", false)));
        for (Object value : new Object[]{true, "true", "false", 1, JSONObject.NULL})
            assertFalse(ApiConfigPolicy.acceptsLegacyDeleteFlag(new JSONObject().put("deleteKey", value)));
    }
}
