package ru.geministation.buttonhelper;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Linux evdev input_event; time_t/long are 32-bit in a 32-bit process. */
final class InputEvent {
    static final int EV_KEY = 1;
    static final int KEY_SEARCH = 217;
    static int size(boolean is64Bit) { return is64Bit ? 24 : 16; }
    static boolean isSearchDown(byte[] event, boolean is64Bit) {
        if (event.length != size(is64Bit)) return false;
        ByteBuffer bytes = ByteBuffer.wrap(event).order(ByteOrder.nativeOrder());
        int offset = is64Bit ? 16 : 8;
        return Short.toUnsignedInt(bytes.getShort(offset)) == EV_KEY
                && Short.toUnsignedInt(bytes.getShort(offset + 2)) == KEY_SEARCH
                && bytes.getInt(offset + 4) == 1;
    }
    private InputEvent() { }
}
