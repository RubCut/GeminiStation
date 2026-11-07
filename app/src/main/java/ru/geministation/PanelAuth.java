package ru.geministation;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;

/** Android-backed storage for the panel's salted verifier. */
final class PanelAuth {
    private final PanelAuthEngine engine;
    PanelAuth(Context c) {
        SharedPreferences p = c.getSharedPreferences("panel_auth", Context.MODE_PRIVATE);
        engine = new PanelAuthEngine(new PanelAuthEngine.Store() {
            public boolean enabled() { return p.getBoolean("enabled", false); }
            public String salt() { return p.getString("salt", ""); }
            public String hash() { return p.getString("hash", ""); }
            public boolean save(String salt, String hash) { return p.edit().putBoolean("enabled", true).putString("salt", salt).putString("hash", hash).commit(); }
            public boolean clear() { return p.edit().clear().commit(); }
        }, SystemClock::elapsedRealtime);
    }
    boolean enabled() { return engine.enabled(); }
    static boolean validPassword(String value) { return PanelAuthEngine.validPassword(value); }
    String setPassword(String value) throws Exception { return engine.setPassword(value); }
    boolean disable() { return engine.disable(); }
    String login(String peer, String password) throws Exception { return engine.login(peer, password); }
    boolean authorized(String cookie) { return engine.authorized(cookie); }
    static String cookie(String token) { return PanelAuthEngine.cookie(token); }
    static String expiredCookie() { return PanelAuthEngine.expiredCookie(); }
}
