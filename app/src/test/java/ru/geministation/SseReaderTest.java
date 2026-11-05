package ru.geministation;

import java.io.IOException;
import java.io.StringReader;
import org.junit.Test;
import static org.junit.Assert.*;

public class SseReaderTest {
    @Test public void handlesMultipleLinesCrLfCommentsAndLastEventWithoutNewline() throws Exception {
        SseReader sse = new SseReader(new StringReader(": keepalive\r\nevent: message\r\ndata: {\r\ndata: \"audio\":1}\r\n\r\ndata: [DONE]"));
        assertEquals("{\n\"audio\":1}", sse.next());
        assertEquals("[DONE]", sse.next());
        assertNull(sse.next());
    }
    @Test public void supportsEmptyDataAndIgnoresUnknownFields() throws Exception {
        SseReader sse = new SseReader(new StringReader("id: 2\ndata\n\ndata: next\n\n"));
        assertEquals("", sse.next()); assertEquals("next", sse.next()); assertNull(sse.next());
    }
    @Test(expected = IOException.class) public void rejectsOversizedLine() throws Exception {
        char[] chars = new char[1024 * 1024 + 2];
        java.util.Arrays.fill(chars, 'a');
        new SseReader(new StringReader(new String(chars))).next();
    }
}
