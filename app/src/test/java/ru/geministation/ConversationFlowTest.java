package ru.geministation;

import org.junit.Test;
import static org.junit.Assert.*;

public class ConversationFlowTest {
    @Test public void completeConversationWithFollowUp() {
        ConversationFlow flow = new ConversationFlow();
        assertTrue(flow.initialListening());
        assertTrue(flow.recognized());
        assertEquals(ConversationFlow.Phase.THINKING, flow.phase());
        assertTrue(flow.answered());
        assertEquals(ConversationFlow.Phase.SPEAKING, flow.phase());
        assertTrue(flow.followUp());
        assertTrue(flow.recognized());
        assertEquals(ConversationFlow.Phase.THINKING, flow.phase());
        assertTrue(flow.answered());
        assertTrue(flow.followUp());
        flow.finish();
        assertEquals(ConversationFlow.Phase.FINISHED, flow.phase());
        assertFalse(flow.recognized());
        assertFalse(flow.followUp());
    }
    @Test public void noResultsOrResponsesFromWrongPhase() {
        ConversationFlow flow = new ConversationFlow();
        assertFalse(flow.recognized());
        assertFalse(flow.answered());
        assertFalse(flow.followUp());
        assertTrue(flow.initialListening());
        assertFalse(flow.initialListening());
        assertFalse(flow.answered());
        assertTrue(flow.recognized());
        assertFalse(flow.recognized());
        assertFalse(flow.followUp());
    }
    @Test public void finishedSessionCannotBeResurrected() {
        ConversationFlow flow = new ConversationFlow();
        flow.initialListening(); flow.finish();
        assertFalse(flow.initialListening());
        assertFalse(flow.recognized());
        assertFalse(flow.answered());
        assertFalse(flow.followUp());
    }
}
