package ru.geministation;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** A key stored in Android Keystore protects the API key in app-private preferences. */
final class SecretStore {
    private static final Object LOCK = new Object();
    private static final String ALIAS = "geministation-api-key";
    private final Context context;
    private final String preferences;
    SecretStore(Context context) { this(context, "secrets"); }
    SecretStore(Context context, String preferences) { this.context = context.getApplicationContext(); this.preferences = preferences; }

    private SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (!store.containsAlias(ALIAS)) {
            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
            generator.generateKey();
        }
        return (SecretKey) store.getKey(ALIAS, null);
    }
    void save(String value) throws Exception { synchronized (LOCK) {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key());
        String encrypted = Base64.encodeToString(cipher.doFinal(value.getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP);
        String iv = Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP);
        if (!context.getSharedPreferences(preferences, Context.MODE_PRIVATE).edit()
                .putString("key", encrypted).putString("iv", iv).commit()) {
            throw new IllegalStateException("Could not save key");
        }
    } }
    String load() throws Exception { synchronized (LOCK) {
        android.content.SharedPreferences prefs = context.getSharedPreferences(preferences, Context.MODE_PRIVATE);
        String value = prefs.getString("key", "");
        if (value.isEmpty()) return "";
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Base64.decode(prefs.getString("iv", ""), Base64.NO_WRAP)));
        return new String(cipher.doFinal(Base64.decode(value, Base64.NO_WRAP)), StandardCharsets.UTF_8);
    } }
    void clear() { synchronized (LOCK) {
        // apply() updates memory immediately but may lose deletion on a crash.
        if (!context.getSharedPreferences(preferences, Context.MODE_PRIVATE).edit().clear().commit())
            throw new IllegalStateException("Could not persist secret removal");
    } }
}
