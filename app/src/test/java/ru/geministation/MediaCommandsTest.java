package ru.geministation;

import android.view.KeyEvent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class MediaCommandsTest {
    @Test public void sendsDownThenUpForEachAllowedCommand() {
        int[] expected = {KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_STOP,
                KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS,
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_MEDIA_REWIND};
        MediaCommands.Command[] commands = MediaCommands.Command.values();
        assertEquals(expected.length, commands.length);
        for (int i = 0; i < commands.length; i++) {
            List<String> events = new ArrayList<>();
            assertEquals(commands[i], MediaCommands.parse(commands[i].id));
            assertTrue(MediaCommands.send(commands[i], (code, down) -> events.add(code + ":" + down)));
            assertEquals(Arrays.asList(expected[i] + ":true", expected[i] + ":false"), events);
        }
    }
    @Test public void invalidOrFailedCommandsNeverReportSuccess() {
        assertNull(MediaCommands.parse(null));
        assertNull(MediaCommands.parse("volume_up"));
        assertFalse(MediaCommands.send(null, (code, down) -> fail()));
        assertFalse(MediaCommands.send(MediaCommands.Command.PLAY, null));
        assertFalse(MediaCommands.send(MediaCommands.Command.PLAY, (code, down) -> { throw new SecurityException(); }));
    }
}
