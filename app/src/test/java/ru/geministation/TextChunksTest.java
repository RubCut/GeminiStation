package ru.geministation;

import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class TextChunksTest {
    @Test public void preservesTextAndDoesNotSplitSupplementaryCharacters() {
        String text = "Привет мир. 😀 " + "длинныйтекст".repeat(20) + " конец";
        List<String> parts = TextChunks.split(text, 17);
        assertEquals(text, String.join("", parts));
        for (String part : parts) {
            assertTrue(part.length() <= 17);
            assertFalse(Character.isLowSurrogate(part.charAt(0)));
            assertFalse(Character.isHighSurrogate(part.charAt(part.length() - 1)));
        }
    }
    @Test public void handlesLimitAndInvalidValues() {
        assertEquals(1, TextChunks.split("ok", 2).size());
        try { TextChunks.split("", 4); fail(); } catch (IllegalArgumentException expected) { }
        try { TextChunks.split("text", 1); fail(); } catch (IllegalArgumentException expected) { }
    }
    @Test public void acceptsOnlyPackageStyleEngineIdentifiers() {
        assertTrue(AndroidTtsEngines.validPackage("com.example.tts"));
        assertFalse(AndroidTtsEngines.validPackage("../engine"));
        assertFalse(AndroidTtsEngines.validPackage("not-a-package"));
        assertFalse(AndroidTtsEngines.validPackage(null));
    }
}
