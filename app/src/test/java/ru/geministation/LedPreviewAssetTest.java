package ru.geministation;

import org.junit.Test;
import static org.junit.Assert.*;

public class LedPreviewAssetTest {
    @Test public void exposesOnlyFixedPreviews() {
        assertEquals("led/listening.gif", LedPreviewAsset.assetForPath("/api/led/preview/listening"));
        assertEquals("led/thinking.gif", LedPreviewAsset.assetForPath("/api/led/preview/thinking"));
        assertEquals("led/speaking.gif", LedPreviewAsset.assetForPath("/api/led/preview/speaking"));
        assertNull(LedPreviewAsset.assetForPath("/api/led/preview/clear"));
        assertNull(LedPreviewAsset.assetForPath("/api/led/preview/../../index.html"));
        assertNull(LedPreviewAsset.assetForPath(null));
    }
}
