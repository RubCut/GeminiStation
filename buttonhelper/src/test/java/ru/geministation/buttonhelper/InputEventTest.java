package ru.geministation.buttonhelper;

import static org.junit.Assert.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.Test;

public class InputEventTest {
    private byte[] event(boolean bit64, int type, int key, int value) {
        byte[] bytes = new byte[InputEvent.size(bit64)];
        ByteBuffer b = ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder());
        int offset = bit64 ? 16 : 8;
        b.putShort(offset, (short) type);
        b.putShort(offset + 2, (short) key);
        b.putInt(offset + 4, value);
        return bytes;
    }
    @Test public void onlyKeySearchDownInBothLayouts() {
        for (boolean bit64 : new boolean[] {false, true}) {
            assertTrue(InputEvent.isSearchDown(event(bit64, 1, 217, 1), bit64));
            assertFalse(InputEvent.isSearchDown(event(bit64, 1, 217, 0), bit64));
            assertFalse(InputEvent.isSearchDown(event(bit64, 1, 217, 2), bit64));
            assertFalse(InputEvent.isSearchDown(event(bit64, 1, 218, 1), bit64));
            assertFalse(InputEvent.isSearchDown(event(bit64, 4, 217, 1), bit64));
            assertFalse(InputEvent.isSearchDown(new byte[0], bit64));
        }
    }
}
