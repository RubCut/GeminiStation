package ru.geministation;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.Test;
import static org.junit.Assert.*;

public class LedAssetUrlTest {
    private String url(String frame, String bytes) throws Exception {
        return LedAssetUrl.build(frame, new ByteArrayInputStream(bytes.getBytes(StandardCharsets.UTF_8)));
    }
    @Test public void changedAssetUsesDifferentUrlAndSameBytesAreStable() throws Exception {
        String first = url("thinking", "old image");
        assertTrue(first.matches("http://127\\.0\\.0\\.1:6529/led/thinking\\.gif\\?v=[0-9a-f]{16}"));
        assertEquals(first, url("thinking", "old image"));
        assertNotEquals(first, url("thinking", "new image"));
        assertNotEquals(first, url("speaking", "old image"));
        assertTrue(url("clear", "clear").contains("/clear.gif?v="));
    }
    @Test(expected = IllegalArgumentException.class) public void pathTraversalRejected() throws Exception {
        url("../thinking", "image");
    }
}
