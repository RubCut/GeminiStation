package ru.geministation;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class PluginArchiveTest {
    private static final String MANIFEST = "{\"id\":\"example\",\"name\":\"Пример\",\"version\":\"1\",\"permissions\":[\"adb_shell\"],\"tools\":[{\"name\":\"check\",\"handler\":\"check\",\"description\":\"Проверка\",\"parameters\":{\"type\":\"OBJECT\",\"properties\":{\"message\":{\"type\":\"STRING\"}},\"required\":[\"message\"]}}]}";
    private static byte[] zip(String... pairs) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream out = new ZipOutputStream(bytes)) {
            for (int i = 0; i < pairs.length; i += 2) { out.putNextEntry(new ZipEntry(pairs[i])); out.write(pairs[i + 1].getBytes(StandardCharsets.UTF_8)); out.closeEntry(); }
        }
        return bytes.toByteArray();
    }
    @Test public void parsesMetadataWithoutRunningCodeAndValidatesArguments() throws Exception {
        PluginArchive archive = PluginArchive.parse(zip("plugin.json", MANIFEST, "plugin.py", "raise Exception('must not run')"));
        assertTrue(archive.manifest.shell);
        assertEquals(64, archive.digest.length());
        JSONObject tool = archive.manifest.tools.getJSONObject(0), schema = tool.getJSONObject("parameters");
        assertEquals("plugin_7_example_check", tool.getString("name"));
        assertTrue(PluginManifest.validArgs(schema, new JSONObject().put("message", "hello")));
        assertFalse(PluginManifest.validArgs(schema, new JSONObject()));
        assertFalse(PluginManifest.validArgs(schema, new JSONObject().put("message", 5)));
        assertFalse(PluginManifest.validArgs(schema, new JSONObject().put("message", "ok").put("command", "id")));
    }
    @Test public void rejectsPathsExtrasOversizeAndUnknownCapabilities() throws Exception {
        for (byte[] archive : new byte[][] {
            zip("plugin.json", MANIFEST, "../plugin.py", "hello"),
            zip("plugin.json", MANIFEST, "plugin.py", "hello", "other", "x"),
            zip("plugin.json", MANIFEST, "plugin.py", "x".repeat(65537)),
            zip("plugin.json", MANIFEST.replace("adb_shell", "unknown"), "plugin.py", "x")
        }) {
            try { PluginArchive.parse(archive); fail("invalid ZIP accepted"); } catch (IllegalArgumentException expected) { }
        }
    }
    @Test public void lengthPrefixedToolNamesAvoidIdCollisions() throws Exception {
        String first = MANIFEST.replace("example", "a_b").replace("check", "c");
        String second = MANIFEST.replace("example", "a").replace("check", "b_c");
        assertNotEquals(PluginManifest.parse(new JSONObject(first)).tools.getJSONObject(0).getString("name"),
                PluginManifest.parse(new JSONObject(second)).tools.getJSONObject(0).getString("name"));
    }
}
