package ru.geministation;

import android.content.Context;
import android.util.AtomicFile;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import org.json.JSONObject;

/** Fixed names only. Uploaded files remain private and never become user-controlled paths. */
final class LedAnimations {
    private static final Object LOCK = new Object();
    static boolean mode(String mode) { return "listening".equals(mode) || "thinking".equals(mode) || "speaking".equals(mode); }
    static boolean style(String style) { return "new".equals(style) || "classic".equals(style) || "hybrid".equals(style) || "custom".equals(style); }
    private static AtomicFile file(Context c, String mode) { return new AtomicFile(new File(c.getFilesDir(), "led_custom_" + mode + ".gif")); }
    static String selected(Context c, String mode) {
        if (!mode(mode)) throw new IllegalArgumentException("mode");
        String selected = c.getSharedPreferences("led_animations", Context.MODE_PRIVATE).getString(mode, "new");
        if (!style(selected) || ("custom".equals(selected) && !file(c, mode).getBaseFile().isFile())) return "new";
        return selected;
    }
    static boolean select(Context c, String mode, String style) {
        synchronized (LOCK) {
            if (!mode(mode) || !style(style) || ("custom".equals(style) && !file(c, mode).getBaseFile().isFile())) return false;
            return c.getSharedPreferences("led_animations", Context.MODE_PRIVATE).edit().putString(mode, style).commit();
        }
    }
    static boolean upload(Context c, String mode, byte[] gif) throws Exception {
        if (!mode(mode) || !GifValidation.valid(gif)) return false;
        synchronized (LOCK) {
            AtomicFile f = file(c, mode); FileOutputStream out = null;
            try { out = f.startWrite(); out.write(gif); f.finishWrite(out); }
            catch (Exception e) { if (out != null) f.failWrite(out); throw e; }
            return select(c, mode, "custom");
        }
    }
    static byte[] read(Context c, String mode, String style) throws Exception {
        if (!mode(mode) || !style(style)) throw new IllegalArgumentException("invalid animation");
        synchronized (LOCK) {
            InputStream in = "custom".equals(style) ? file(c, mode).openRead() : c.getAssets().open("led/" + ("new".equals(style) ? "" : style + "/") + mode + ".gif");
            try (InputStream source = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buf = new byte[8192]; int n;
                while ((n = source.read(buf)) != -1) { if (out.size() + n > GifValidation.MAX_BYTES) throw new IllegalArgumentException("too large"); out.write(buf, 0, n); }
                return out.toByteArray();
            }
        }
    }
    static byte[] current(Context c, String mode) throws Exception {
        if ("clear".equals(mode)) try (InputStream in = c.getAssets().open("led/clear.gif"); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] b = new byte[4096]; int n; while ((n = in.read(b)) != -1) out.write(b, 0, n); return out.toByteArray();
        }
        synchronized (LOCK) { return read(c, mode, selected(c, mode)); }
    }
    static JSONObject info(Context c) throws Exception {
        JSONObject choices = new JSONObject(), custom = new JSONObject();
        synchronized (LOCK) { for (String mode : new String[]{"listening", "thinking", "speaking"}) {
            choices.put(mode, selected(c, mode)); custom.put(mode, file(c, mode).getBaseFile().isFile());
        } }
        return new JSONObject().put("choices", choices).put("custom", custom);
    }
    private LedAnimations() { }
}
