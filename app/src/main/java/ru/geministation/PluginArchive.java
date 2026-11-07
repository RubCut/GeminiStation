package ru.geministation;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.json.JSONObject;

/** Two fixed root files only; never extracts paths to disk or executes code during installation. */
final class PluginArchive {
    static final int MAX_ZIP = 131072, MAX_SOURCE = 65536, MAX_MANIFEST = 16384;
    final JSONObject manifestJson;
    final PluginManifest manifest;
    final String source, digest;
    private PluginArchive(JSONObject json, PluginManifest manifest, String source, String digest) {
        this.manifestJson = json; this.manifest = manifest; this.source = source; this.digest = digest;
    }
    static PluginArchive parse(byte[] zip) throws Exception {
        if (zip == null || zip.length < 1 || zip.length > MAX_ZIP) throw new IllegalArgumentException("Недопустимый размер ZIP");
        String manifest = null, source = null; int count = 0;
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                if (++count > 2 || entry.isDirectory()) throw new IllegalArgumentException("В ZIP нужны только plugin.json и plugin.py");
                String name = entry.getName(); int limit;
                if ("plugin.json".equals(name) && manifest == null) limit = MAX_MANIFEST;
                else if ("plugin.py".equals(name) && source == null) limit = MAX_SOURCE;
                else throw new IllegalArgumentException("В ZIP нужны только plugin.json и plugin.py");
                ByteArrayOutputStream bytes = new ByteArrayOutputStream(); byte[] buffer = new byte[4096]; int n;
                while ((n = in.read(buffer)) != -1) {
                    if (bytes.size() + n > limit) throw new IllegalArgumentException("Файл плагина слишком большой");
                    bytes.write(buffer, 0, n);
                }
                String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes.toByteArray())).toString();
                if ("plugin.json".equals(name)) manifest = text; else source = text;
                in.closeEntry();
            }
        }
        if (manifest == null || source == null || source.isEmpty()) throw new IllegalArgumentException("Нет манифеста или кода");
        JSONObject json = new JSONObject(manifest);
        if (json.has("settings") && (json.optJSONObject("settings") == null
                || json.getJSONObject("settings").toString().getBytes(StandardCharsets.UTF_8).length > 8192))
            throw new IllegalArgumentException("Настройки по умолчанию должны быть JSON-объектом до 8 КиБ");
        PluginManifest spec = PluginManifest.parse(json);
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(zip); StringBuilder digest = new StringBuilder();
        for (byte b : hash) digest.append(String.format(java.util.Locale.ROOT, "%02x", b & 0xff));
        return new PluginArchive(json, spec, source, digest.toString());
    }
}
