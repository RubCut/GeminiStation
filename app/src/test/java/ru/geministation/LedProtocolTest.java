package ru.geministation;

import java.nio.charset.StandardCharsets;
import org.junit.Test;
import static org.junit.Assert.*;

public class LedProtocolTest {
    @Test public void encodesScreenCommandAsTwoProtobufStrings() {
        String url = "http://127.0.0.1:6529/led/thinking.gif";
        byte[] encoded = LedProtocol.command(url);
        assertEquals(10, encoded[0]);
        int firstLength = encoded[1] & 255;
        assertEquals("draw_led_screen", new String(encoded, 2, firstLength, StandardCharsets.UTF_8));
        int pos = 2 + firstLength;
        assertEquals(18, encoded[pos++]);
        int length = encoded[pos++] & 255;
        assertTrue(length < 128);
        assertEquals(encoded.length - pos, length);
        assertEquals("{\"animation_sequence\":[{\"frontal_led_image\":\"" + url + "\"}]}", new String(encoded, pos, length, StandardCharsets.UTF_8));
    }
}
