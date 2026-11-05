package ru.geministation;

/** Public, read-only previews of the three built-in animations. No user paths. */
final class LedPreviewAsset {
    static String assetForPath(String path) {
        if ("/api/led/preview/listening".equals(path)) return "led/listening.gif";
        if ("/api/led/preview/thinking".equals(path)) return "led/thinking.gif";
        if ("/api/led/preview/speaking".equals(path)) return "led/speaking.gif";
        if (path != null && path.matches("/api/led/preview/(classic|hybrid|new)/(listening|thinking|speaking)")) {
            String[] parts = path.split("/");
            return "led/" + ("new".equals(parts[4]) ? "" : parts[4] + "/") + parts[5] + ".gif";
        }
        return null;
    }
    private LedPreviewAsset() { }
}
