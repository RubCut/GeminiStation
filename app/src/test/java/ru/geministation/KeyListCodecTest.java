package ru.geministation;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class KeyListCodecTest {
    @Test public void migratesSingleKeyWithoutChangingItsValue() throws Exception {
        String old = "legacy-key";
        assertTrue(KeyListCodec.legacy(old));
        List<KeyRotation.Entry> keys = KeyListCodec.decode(old, () -> "opaque-id");
        assertEquals(1, keys.size());
        String encoded = KeyListCodec.encode(keys);
        assertFalse(KeyListCodec.legacy(encoded));
        List<KeyRotation.Entry> restored = KeyListCodec.decode(encoded, () -> { throw new AssertionError(); });
        assertEquals("legacy-key", restored.get(0).secret);
        assertEquals("opaque-id", restored.get(0).id);
    }
    @Test public void preservesOrderAndEmptyList() throws Exception {
        List<KeyRotation.Entry> keys = Arrays.asList(new KeyRotation.Entry("1", "first"), new KeyRotation.Entry("2", "second"));
        List<KeyRotation.Entry> restored = KeyListCodec.decode(KeyListCodec.encode(keys), () -> "unused");
        assertEquals("first", restored.get(0).secret);
        assertEquals("second", restored.get(1).secret);
        assertTrue(KeyListCodec.decode("", () -> "unused").isEmpty());
    }
    @Test public void malformedVersionedDataMustNotBecomeALegacyKey() throws Exception {
        try { KeyListCodec.decode("GeminiKeyListV1:{bad", () -> "unused"); fail(); }
        catch (Exception expected) { /* report corruption instead of overwriting it */ }
    }
}
