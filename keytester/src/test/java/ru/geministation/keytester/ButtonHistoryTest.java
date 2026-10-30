package ru.geministation.keytester;

import org.junit.Test;
import static org.junit.Assert.*;

public class ButtonHistoryTest {
    @Test public void historyPersistsLatestHundredEvents() {
        ButtonHistory history = new ButtonHistory("");
        for (int i = 0; i < 105; i++) history.add("keyCode=" + i);
        assertEquals(100, history.size());
        assertEquals("keyCode=104", history.latest());
        ButtonHistory restored = new ButtonHistory(history.serialize());
        assertEquals(100, restored.size());
        assertTrue(restored.serialize().startsWith("keyCode=5\n"));
        assertEquals("keyCode=104", restored.latest());
        restored.clear();
        assertNull(restored.latest());
        assertEquals("", restored.serialize());
    }
    @Test public void deviceNameCannotAddLinesOrBecomeUnbounded() {
        assertEquals("keyboard injected", ButtonHistory.oneLine("keyboard\ninjected", 80));
        assertEquals("abcdef", ButtonHistory.oneLine("abcdefghi", 6));
        assertEquals("—", ButtonHistory.oneLine(null, 80));
        ButtonHistory history = new ButtonHistory("");
        history.add("key\ncode");
        assertEquals("key code", new ButtonHistory(history.serialize()).latest());
    }
}
