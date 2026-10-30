package ru.geministation;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/** The externalCommandBypass envelope described by Station Max Studio (MIT). */
final class LedProtocol {
    private static void varint(ByteArrayOutputStream out, int value) {
        while (value >= 128) { out.write((value & 127) | 128); value >>>= 7; }
        out.write(value);
    }
    private static void field(ByteArrayOutputStream out, int number, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        varint(out, (number << 3) | 2); varint(out, bytes.length); out.write(bytes, 0, bytes.length);
    }
    static byte[] command(String url) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        field(out, 1, "draw_led_screen");
        field(out, 2, "{\"animation_sequence\":[{\"frontal_led_image\":\"" + url + "\"}]}");
        return out.toByteArray();
    }
    private LedProtocol() { }
}
