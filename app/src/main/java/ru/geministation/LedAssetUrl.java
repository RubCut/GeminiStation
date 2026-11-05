package ru.geministation;

import java.io.InputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Station firmware caches GIFs by URL, so include a fingerprint of the asset. */
final class LedAssetUrl {
    static String build(String name, InputStream source) throws IOException {
        if (!"listening".equals(name) && !"thinking".equals(name) && !"speaking".equals(name) && !"clear".equals(name))
            throw new IllegalArgumentException("Unknown LED asset");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] data = new byte[8192];
            int count;
            while ((count = source.read(data)) != -1) digest.update(data, 0, count);
            byte[] hash = digest.digest();
            StringBuilder hex = new StringBuilder(16);
            final char[] digits = "0123456789abcdef".toCharArray();
            for (int i = 0; i < 8; i++) {
                int value = hash[i] & 255;
                hex.append(digits[value >>> 4]).append(digits[value & 15]);
            }
            return "http://127.0.0.1:6529/led/" + name + ".gif?v=" + hex;
        } catch (NoSuchAlgorithmException e) { throw new IOException("SHA-256 unavailable", e); }
    }
    private LedAssetUrl() { }
}
