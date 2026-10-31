package ru.geministation.buttonhelper;

import java.nio.charset.StandardCharsets;
import org.junit.Test;
import ru.geministation.helperprotocol.HelperProtocol;
import static org.junit.Assert.*;

public class ShellExecutorTest {
    @Test public void runsCommandAndCapturesExitAndOutput() throws Exception {
        HelperProtocol.Result result = ShellExecutor.run("/bin/sh", new HelperProtocol.Request("printf test; exit 7", 3));
        assertEquals(7, result.exitCode); assertFalse(result.timedOut); assertEquals("test", new String(result.output, StandardCharsets.UTF_8));
    }
    @Test public void boundsOutputAndStopsWaitingOnTimeout() throws Exception {
        HelperProtocol.Result result = ShellExecutor.run("/bin/sh", new HelperProtocol.Request("head -c 70000 /dev/zero", 3));
        assertEquals(0, result.exitCode); assertTrue(result.truncated); assertEquals(HelperProtocol.MAX_OUTPUT, result.output.length);
        result = ShellExecutor.run("/bin/sh", new HelperProtocol.Request("sleep 5", 1));
        assertTrue(result.timedOut); assertEquals(-1, result.exitCode);
    }
}
