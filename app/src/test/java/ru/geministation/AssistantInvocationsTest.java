package ru.geministation;

import org.junit.Test;
import static org.junit.Assert.*;

public class AssistantInvocationsTest {
    @Test public void acceptsTheThreeSupportedSystemAssistantActions() {
        assertTrue(AssistantInvocations.isInvocation("android.intent.action.ASSIST"));
        assertTrue(AssistantInvocations.isInvocation("android.intent.action.VOICE_COMMAND"));
        assertTrue(AssistantInvocations.isInvocation("android.speech.action.WEB_SEARCH"));
        assertFalse(AssistantInvocations.isInvocation(null));
        assertFalse(AssistantInvocations.isInvocation("android.intent.action.MAIN"));
    }
}
