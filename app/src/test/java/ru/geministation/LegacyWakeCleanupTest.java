package ru.geministation;

import java.io.File;
import java.nio.file.Files;
import org.junit.Test;
import static org.junit.Assert.*;

public class LegacyWakeCleanupTest {
    @Test public void doesNotTraverseReplacedModelDirectory() throws Exception {
        File files = Files.createTempDirectory("wake-cleanup-link").toFile();
        File elsewhere = Files.createTempDirectory("wake-cleanup-outside").toFile();
        File outside = new File(elsewhere, "means"); assertTrue(outside.createNewFile());
        File link = new File(files, "wake-model-v1");
        Files.createSymbolicLink(link.toPath(), elsewhere.toPath());
        assertEquals(0, LegacyWakeCleanup.remove(files));
        assertTrue(outside.exists());
        assertTrue(link.delete()); assertTrue(outside.delete()); assertTrue(elsewhere.delete()); assertTrue(files.delete());
    }
    @Test public void deletesOnlyKnownFilesAndLeavesOtherData() throws Exception {
        File files = Files.createTempDirectory("wake-cleanup").toFile();
        File dir = new File(files, "wake-model-v1"); assertTrue(dir.mkdir());
        File obsolete = new File(dir, "means"); assertTrue(obsolete.createNewFile());
        File partial = new File(dir, "mdef.tmp"); assertTrue(partial.createNewFile());
        File extra = new File(dir, "user-data.txt"); assertTrue(extra.createNewFile());
        File outside = new File(files, "means"); assertTrue(outside.createNewFile());
        assertEquals(2, LegacyWakeCleanup.remove(files));
        assertFalse(obsolete.exists()); assertFalse(partial.exists());
        assertTrue(extra.exists()); assertTrue(outside.exists()); assertTrue(dir.exists());
        assertEquals(0, LegacyWakeCleanup.remove(files));
        assertTrue(extra.delete()); assertTrue(dir.delete()); assertTrue(outside.delete()); assertTrue(files.delete());
    }
}
