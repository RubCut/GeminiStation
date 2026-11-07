package ru.geministation;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongSupplier;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/** Pure authentication core; the backing store contains only salt and a password verifier. */
final class PanelAuthEngine {
    interface Store {
        boolean enabled(); String salt(); String hash();
        boolean save(String salt, String hash); boolean clear();
    }
    private static final int ITERATIONS = 120000, MAX_SESSIONS = 16;
    private static final long SESSION_MS = 12 * 60 * 60 * 1000L, WINDOW_MS = 60000;
    private final Store store; private final LongSupplier clock; private final SecureRandom random = new SecureRandom();
    private final Map<String, Long> sessions = new LinkedHashMap<>();
    private final LinkedHashMap<String, long[]> attempts = new LinkedHashMap<>();
    PanelAuthEngine(Store store, LongSupplier clock) { this.store = store; this.clock = clock; }
    synchronized boolean enabled() { return store.enabled(); }
    private static byte[] derive(String password, byte[] salt) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, ITERATIONS, 256);
        try { return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(); }
        finally { spec.clearPassword(); }
    }
    static boolean validPassword(String value) { return value != null && value.length() >= 8 && value.length() <= 128 && value.indexOf('\0') < 0; }
    synchronized String setPassword(String password) throws Exception {
        if (!validPassword(password)) throw new IllegalArgumentException("invalid password");
        byte[] salt = new byte[16]; random.nextBytes(salt); byte[] hash = derive(password, salt);
        boolean saved;
        try { saved = store.save(Base64.getEncoder().encodeToString(salt), Base64.getEncoder().encodeToString(hash)); }
        finally { Arrays.fill(hash, (byte) 0); }
        if (!saved) throw new IllegalStateException("save failed");
        sessions.clear(); attempts.clear(); return newSession();
    }
    synchronized boolean disable() {
        if (!store.clear()) return false;
        sessions.clear(); attempts.clear(); return true;
    }
    synchronized String login(String peer, String password) throws Exception {
        if (!enabled() || !validPassword(password)) return null;
        long now = clock.getAsLong();
        long[] state = attempts.get(peer);
        if (state == null || now - state[0] >= WINDOW_MS) { state = new long[]{now, 0}; attempts.put(peer, state); }
        while (attempts.size() > 128) attempts.remove(attempts.keySet().iterator().next());
        if (state[1] >= 5) return null;
        state[1]++;
        byte[] salt = Base64.getDecoder().decode(store.salt()), expected = Base64.getDecoder().decode(store.hash());
        if (salt.length != 16 || expected.length != 32) throw new IllegalStateException("invalid verifier");
        byte[] actual = derive(password, salt);
        boolean matches = MessageDigest.isEqual(expected, actual); Arrays.fill(actual, (byte) 0);
        if (!matches) return null;
        attempts.remove(peer); return newSession();
    }
    private String newSession() {
        long now = clock.getAsLong();
        Iterator<Map.Entry<String, Long>> it = sessions.entrySet().iterator();
        while (it.hasNext()) if (now >= it.next().getValue()) it.remove();
        while (sessions.size() >= MAX_SESSIONS) sessions.remove(sessions.keySet().iterator().next());
        byte[] token = new byte[32]; random.nextBytes(token);
        String value = Base64.getUrlEncoder().withoutPadding().encodeToString(token);
        sessions.put(value, now + SESSION_MS); return value;
    }
    synchronized boolean authorized(String cookie) {
        if (!enabled()) return true;
        if (cookie == null || cookie.length() > 4096) return false;
        for (String part : cookie.split(";")) {
            String token = part.trim();
            if (!token.startsWith("gemini_session=")) continue;
            Long expiry = sessions.get(token.substring("gemini_session=".length()));
            return expiry != null && clock.getAsLong() < expiry;
        }
        return false;
    }
    static String cookie(String token) { return "gemini_session=" + token + "; Path=/; HttpOnly; SameSite=Strict"; }
    static String expiredCookie() { return "gemini_session=; Path=/; HttpOnly; SameSite=Strict; Max-Age=0"; }
}
