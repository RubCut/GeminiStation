package ru.geministation;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.Test;
import static org.junit.Assert.*;

public class LedAssetTest {
    private File asset(String name) {
        File file = new File("src/main/assets/led/" + name + ".gif");
        return file.exists() ? file : new File("app/src/main/assets/led/" + name + ".gif");
    }
    private int u(byte[] data, int i) { return data[i] & 255; }
    private int word(byte[] data, int i) { return u(data, i) | (u(data, i + 1) << 8); }
    private int skipBlocks(byte[] data, int pos) {
        while (true) {
            assertTrue("truncated GIF", pos < data.length);
            int len = u(data, pos++);
            if (len == 0) return pos;
            pos += len;
        }
    }
    @Test public void animationsAreShortFiniteAndFitTheMatrix() throws Exception {
        for (String name : new String[]{"listening", "thinking", "speaking", "clear"}) {
            File file = asset(name); assertTrue(name, file.isFile());
            byte[] data = Files.readAllBytes(file.toPath());
            String text = new String(data, StandardCharsets.ISO_8859_1);
            assertTrue(name, text.startsWith("GIF89a") || text.startsWith("GIF87a"));
            // No infinite loop: the firmware can return to its normal display.
            assertFalse(name, text.contains("NETSCAPE2.0"));
            assertEquals(25, word(data, 6)); assertEquals(16, word(data, 8));
            int packed = u(data, 10), pos = 13;
            if ((packed & 0x80) != 0) pos += 3 * (1 << ((packed & 7) + 1));
            int count = 0, total = 0, delay = 0;
            while (pos < data.length) {
                int marker = u(data, pos++);
                if (marker == 0x3b) break;
                if (marker == 0x21) {
                    int label = u(data, pos++);
                    if (label == 0xf9) {
                        assertEquals(4, u(data, pos++));
                        delay = word(data, pos + 1) * 10;
                        pos += 4;
                        assertEquals(0, u(data, pos++));
                    } else pos = skipBlocks(data, pos);
                } else if (marker == 0x2c) {
                    assertEquals(25, word(data, pos + 4)); assertEquals(16, word(data, pos + 6));
                    int flags = u(data, pos + 8); pos += 9;
                    if ((flags & 0x80) != 0) pos += 3 * (1 << ((flags & 7) + 1));
                    pos++; // LZW minimum code size
                    pos = skipBlocks(data, pos);
                    count++; total += delay; delay = 0;
                } else fail("Unknown GIF block " + marker + " in " + name);
            }
            assertTrue(name, count >= 2);
            assertTrue(name + " duration=" + total, total > 0 && total <= (name.equals("clear") ? 500 : 2500));
        }
    }
}
