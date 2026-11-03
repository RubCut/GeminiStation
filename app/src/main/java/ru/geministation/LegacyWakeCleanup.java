package ru.geministation;

import java.io.File;
import java.io.IOException;

/** Remove only obsolete PocketSphinx files copied by older versions, not arbitrary files. */
final class LegacyWakeCleanup {
    private static final String[] FILES = {"feat.params", "feature_transform", "mdef", "means", "mixture_weights",
            "noisedict", "transition_matrices", "variances", "words.dict", "keywords.list", "active-keywords.list"};

    static int remove(File filesDir) {
        try {
            File parent = filesDir.getCanonicalFile();
            File dir = new File(parent, "wake-model-v1");
            // Never traverse a replaced directory symlink.
            if (!dir.getCanonicalFile().equals(dir) || !dir.isDirectory()) return 0;
            int removed = 0;
            for (String name : FILES) {
                removed += deleteFile(dir, name);
                removed += deleteFile(dir, name + ".tmp");
            }
            // Only succeeds when empty; never recursively delete unknown files.
            dir.delete();
            return removed;
        } catch (IOException | SecurityException e) {
            return 0; // Retry on next service start; listening remains usable.
        }
    }
    private static int deleteFile(File dir, String name) {
        File file = new File(dir, name);
        return file.isFile() && file.delete() ? 1 : 0;
    }
    private LegacyWakeCleanup() { }
}
