package ru.geministation;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;

/** Ask Android to duck other apps. The system and other players decide the level or whether to pause. */
final class BackgroundAudioFocus {
    private final Context context;
    private final AudioManager manager;
    private final AudioFocusRequest request;
    private final AudioFocusSession session;

    BackgroundAudioFocus(Context context) {
        this.context = context.getApplicationContext();
        manager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        session = new AudioFocusSession(new AudioFocusSession.Backend() {
            @Override public boolean request() {
                if (manager == null) return false;
                try { return manager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED; }
                catch (RuntimeException e) { return false; }
            }
            @Override public void abandon() {
                if (manager != null) try { manager.abandonAudioFocusRequest(request); }
                catch (RuntimeException ignored) { }
            }
        });
        AudioAttributes attributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build();
        request = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(attributes).setAcceptsDelayedFocusGain(false)
                .setOnAudioFocusChangeListener(change -> {
                    if (change == AudioManager.AUDIOFOCUS_LOSS) {
                        session.permanentlyLost();
                        AppLog.add("Аудиофокус: потерян");
                    }
                }, new Handler(Looper.getMainLooper())).build();
    }
    void acquire() {
        if (!AppSettings.audioDuckEnabled(context)) { release(); return; }
        boolean wasHeld = session.isHeld();
        if (!session.acquire()) AppLog.add("Аудиофокус: не удалось запросить приглушение");
        else if (!wasHeld) AppLog.add("Аудиофокус: запрошено приглушение фонового звука");
    }
    void release() {
        boolean wasHeld = session.isHeld();
        session.release();
        if (wasHeld) AppLog.add("Аудиофокус: освобождён");
    }
}
