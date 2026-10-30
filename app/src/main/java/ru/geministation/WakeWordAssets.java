package ru.geministation;

import android.content.Context;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** Copy the fixed, build-time-verified model to private storage for native PocketSphinx. */
final class WakeWordAssets {
    static final String[] FILES = {"feat.params", "feature_transform", "mdef", "means", "mixture_weights",
            "noisedict", "transition_matrices", "variances"};
    static final long[] SIZES = {128, 5660, 14135693, 23717444, 658880, 27, 2416, 23717444};

    static File prepare(Context context) throws IOException {
        File model = new File(context.getFilesDir(), "wake-model-v1");
        if (!model.isDirectory() && !model.mkdirs()) throw new IOException("Cannot create wake model directory");
        for (int i = 0; i < FILES.length; i++) copy(context, "pocketsphinx/ru/" + FILES[i], new File(model, FILES[i]), SIZES[i]);
        copy(context, "wakeword/words.dict", new File(model, "words.dict"), -1);
        copy(context, "wakeword/keywords.list", new File(model, "keywords.list"), -1);
        return model;
    }
    private static void copy(Context context, String asset, File output, long expected) throws IOException {
        // Fixed dictionaries are small, and always replaced on startup to pick up updates.
        if (expected > 0 && output.isFile() && output.length() == expected) return;
        File temp = new File(output.getParentFile(), output.getName() + ".tmp");
        try (InputStream in = context.getAssets().open(asset); FileOutputStream out = new FileOutputStream(temp)) {
            byte[] buffer = new byte[32768];
            int n; while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
        }
        if ((expected > 0 && temp.length() != expected) || temp.length() == 0 || !temp.renameTo(output)) {
            temp.delete(); throw new IOException("Cannot install wake model file " + output.getName());
        }
    }
    private WakeWordAssets() { }
}
