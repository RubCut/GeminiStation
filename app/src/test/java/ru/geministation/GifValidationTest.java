package ru.geministation;

import org.junit.Test;
import static org.junit.Assert.*;

public class GifValidationTest {
    private byte[] sample() { return java.util.Base64.getDecoder().decode("R0lGODlhGQAQAIEAAAAAAAAAAAAAAAAAACH/C05FVFNDQVBFMi4wAwEAAAAh+QQAFAAAACwAAAAAGQAQAAAIIgABCBxIsKDBgwgTKlzIsKHDhxAjSpxIsaLFixgzatxoMSAAIfkEARQAAgAsDAAIAAEAAQCBAAAA////AAAAAAAACAQAAwQEADs="); }
    @Test public void acceptsBoundedAnimationAndRejectsTruncatedOrBadDimensions() {
        byte[] gif = sample(); assertTrue(GifValidation.valid(gif));
        for (int n : new int[]{0,1,13,50,gif.length-1}) assertFalse(GifValidation.valid(java.util.Arrays.copyOf(gif,n)));
        byte[] bad = gif.clone(); bad[6]=26; assertFalse(GifValidation.valid(bad));
        bad = gif.clone(); bad[8]=17; assertFalse(GifValidation.valid(bad));
        bad = gif.clone(); bad[0]=0; assertFalse(GifValidation.valid(bad));
        assertFalse(GifValidation.valid(new byte[GifValidation.MAX_BYTES+1]));
        assertFalse(GifValidation.valid(null));
    }
    @Test public void rejectsBrokenCompressedDataAndSuspiciousExtensions() {
        byte[] bad = sample();
        // First frame LZW minimum code size follows its image descriptor.
        for (int i=0;i<bad.length-11;i++) if ((bad[i]&255)==0x2c && bad[i+1]==0 && bad[i+2]==0) { bad[i+10]=1; break; }
        assertFalse(GifValidation.valid(bad));
        bad=sample(); bad[bad.length-1]=0; assertFalse(GifValidation.valid(bad));
    }
    @Test public void bundledStylesAndWhitelistAreValid() throws Exception {
        java.nio.file.Path dir = java.nio.file.Paths.get("src/main/assets/led");
        if (!java.nio.file.Files.exists(dir)) dir = java.nio.file.Paths.get("app/src/main/assets/led");
        for (String style : new String[]{"", "classic/", "hybrid/"}) for (String mode : new String[]{"listening", "thinking", "speaking"})
            assertTrue(style+mode, GifValidation.valid(java.nio.file.Files.readAllBytes(dir.resolve(style+mode+".gif"))));
        assertTrue(LedAnimations.mode("thinking")); assertFalse(LedAnimations.mode("../clear"));
        assertFalse(LedAnimations.style("../../"));
        assertEquals("led/hybrid/thinking.gif", LedPreviewAsset.assetForPath("/api/led/preview/hybrid/thinking"));
        assertEquals("led/listening.gif", LedPreviewAsset.assetForPath("/api/led/preview/new/listening"));
        assertNull(LedPreviewAsset.assetForPath("/api/led/preview/hybrid/../../clear"));
    }
}
