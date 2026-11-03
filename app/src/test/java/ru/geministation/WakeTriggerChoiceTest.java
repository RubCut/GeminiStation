package ru.geministation;

import static org.junit.Assert.*;
import org.junit.Test;

public class WakeTriggerChoiceTest {
    @Test public void personalWinsWhenBothAreReady() {
        assertEquals(WakeTriggerChoice.Source.PERSONAL, new WakeTriggerChoice().choose(1000, true, true, true, true, false));
    }
    @Test public void generalWithoutPersonalWorkIsImmediate() {
        WakeTriggerChoice choice = new WakeTriggerChoice();
        assertEquals(WakeTriggerChoice.Source.GENERAL, choice.choose(1000, false, false, true, true, false));
        assertFalse(choice.waiting());
    }
    @Test public void inFlightPersonalCanWinBeforeDeadline() {
        WakeTriggerChoice choice = new WakeTriggerChoice();
        assertEquals(WakeTriggerChoice.Source.NONE, choice.choose(1000, false, false, true, true, true));
        assertTrue(choice.waiting());
        assertEquals(WakeTriggerChoice.Source.PERSONAL, choice.choose(1100, true, true, false, false, false));
        assertFalse(choice.waiting());
    }
    @Test public void fallbackWhenWorkerFinishesOrDeadlineExpires() {
        WakeTriggerChoice choice = new WakeTriggerChoice();
        assertEquals(WakeTriggerChoice.Source.NONE, choice.choose(1000, false, false, true, true, true));
        assertEquals(WakeTriggerChoice.Source.GENERAL, choice.choose(1100, false, false, false, false, false));
        assertEquals(WakeTriggerChoice.Source.NONE, choice.choose(2000, false, false, true, true, true));
        assertEquals(WakeTriggerChoice.Source.NONE, choice.choose(2100, false, false, true, true, true));
        assertEquals(WakeTriggerChoice.Source.GENERAL, choice.choose(2200, false, false, false, false, true));
        assertFalse(choice.waiting());
    }
    @Test public void rejectedCandidatesDoNotSuppressValidFallback() {
        WakeTriggerChoice choice = new WakeTriggerChoice();
        assertEquals(WakeTriggerChoice.Source.GENERAL, choice.choose(1000, true, false, true, true, false));
        assertEquals(WakeTriggerChoice.Source.NONE, choice.choose(2000, true, false, true, false, false));
        assertEquals(WakeTriggerChoice.Source.NONE, choice.choose(3000, false, true, false, true, false));
    }
}
