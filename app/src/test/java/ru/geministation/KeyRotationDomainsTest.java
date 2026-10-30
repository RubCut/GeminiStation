package ru.geministation;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class KeyRotationDomainsTest {
    private final List<KeyRotation.Entry> keys = Arrays.asList(
            new KeyRotation.Entry("a", "secret-a"), new KeyRotation.Entry("b", "secret-b"), new KeyRotation.Entry("c", "secret-c"));
    @Test public void textAndVoiceAndRecognitionAndYoutubeHaveIndependentSelections() throws Exception {
        KeyRotationDomains domains = new KeyRotationDomains(ignored -> {});
        KeyRotation text = domains.rotation(KeyRotationDomains.Domain.TEXT);
        KeyRotation tts = domains.rotation(KeyRotationDomains.Domain.TTS);
        KeyRotation live = domains.rotation(KeyRotationDomains.Domain.LIVE);
        KeyRotation youtube = domains.rotation(KeyRotationDomains.Domain.YOUTUBE);
        assertEquals("secret-b", text.run(keys, () -> true, key -> {
            if (key.equals("secret-a")) throw new KeyRotation.RateLimit("Gemini API");
            return key;
        }));
        assertEquals("secret-a", tts.attempt(keys).key());
        assertEquals("secret-a", live.attempt(keys).key());
        assertEquals("secret-a", youtube.attempt(keys).key());
        // A voice sample and the actual TTS both use the same TTS domain.
        assertEquals("secret-c", tts.run(keys, () -> true, key -> {
            if (!key.equals("secret-c")) throw new KeyRotation.RateLimit("Gemini TTS");
            return key;
        }));
        assertEquals("secret-c", domains.rotation(KeyRotationDomains.Domain.TTS).attempt(keys).key());
        assertEquals("secret-b", text.attempt(keys).key());
        assertEquals("secret-a", live.attempt(keys).key());
        assertTrue(live.attempt(keys).advance());
        assertEquals("secret-b", live.attempt(keys).key());
        assertEquals("secret-c", tts.attempt(keys).key());
        assertEquals("secret-a", youtube.attempt(keys).key());
    }
    @Test public void partialAudioChangesOnlyFutureTtsWithoutReplay() throws Exception {
        KeyRotationDomains domains = new KeyRotationDomains(ignored -> {});
        int[] calls = {0};
        try {
            domains.rotation(KeyRotationDomains.Domain.TTS).run(keys, () -> true, key -> {
                calls[0]++; throw new KeyRotation.RateLimit("Gemini TTS", false);
            });
            fail();
        } catch (KeyRotation.RateLimit expected) { assertEquals(1, calls[0]); }
        assertEquals("secret-b", domains.rotation(KeyRotationDomains.Domain.TTS).attempt(keys).key());
        assertEquals("secret-a", domains.rotation(KeyRotationDomains.Domain.TEXT).attempt(keys).key());
    }
    @Test public void removalOfPreferredKeyResetsItsDomainAndLaterLimitsStillRotate() {
        KeyRotation rotation = new KeyRotation(() -> {});
        assertTrue(rotation.attempt(keys).advance());
        List<KeyRotation.Entry> remaining = Arrays.asList(keys.get(0), keys.get(2));
        assertEquals("secret-a", rotation.attempt(remaining).key());
        assertTrue(rotation.attempt(remaining).advance());
        assertEquals("secret-c", rotation.attempt(remaining).key());
    }
}
