package ru.geministation;

import org.junit.Test;
import static org.junit.Assert.*;

public class AssistantButtonPolicyTest {
    @Test public void triggersOnceAndConsumesCorrespondingRelease() {
        AssistantButtonPolicy policy = new AssistantButtonPolicy();
        assertEquals(AssistantButtonPolicy.Decision.TRIGGER, policy.key(231, 0, 0, true));
        assertEquals(AssistantButtonPolicy.Decision.CONSUME, policy.key(231, 0, 1, true));
        assertEquals(AssistantButtonPolicy.Decision.CONSUME, policy.key(231, 1, 0, true));
        assertEquals(AssistantButtonPolicy.Decision.TRIGGER, policy.key(231, 0, 0, true));
    }
    @Test public void optInPreventsNewTriggersButDoesNotLeakAConsumedRelease() {
        AssistantButtonPolicy policy = new AssistantButtonPolicy();
        assertEquals(AssistantButtonPolicy.Decision.PASS, policy.key(231, 0, 0, false));
        assertEquals(AssistantButtonPolicy.Decision.PASS, policy.key(231, 1, 0, false));
        assertEquals(AssistantButtonPolicy.Decision.TRIGGER, policy.key(231, 0, 0, true));
        assertEquals(AssistantButtonPolicy.Decision.CONSUME, policy.key(231, 0, 1, false));
        assertEquals(AssistantButtonPolicy.Decision.CONSUME, policy.key(231, 1, 0, false));
        assertEquals(AssistantButtonPolicy.Decision.PASS, policy.key(231, 0, 0, false));
    }
    @Test public void ignoresOtherKeysAndOrphanReleases() {
        AssistantButtonPolicy policy = new AssistantButtonPolicy();
        assertEquals(AssistantButtonPolicy.Decision.PASS, policy.key(66, 0, 0, true));
        assertEquals(AssistantButtonPolicy.Decision.PASS, policy.key(231, 1, 0, true));
        assertEquals(AssistantButtonPolicy.Decision.PASS, policy.key(231, 0, 2, true));
        assertEquals(AssistantButtonPolicy.Decision.TRIGGER, policy.key(231, 0, 0, true));
        policy.reset();
        assertEquals(AssistantButtonPolicy.Decision.PASS, policy.key(231, 1, 0, true));
    }
}
