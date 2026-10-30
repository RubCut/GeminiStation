package ru.geministation;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;

/** Local UI-thread cues. The continuation runs after completion, error, or timeout, never after cancel. */
final class SoundCuePlayer {
    private static final long TIMEOUT_MS = 5000;
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final CueGate gate = new CueGate();
    private MediaPlayer player;
    private Runnable timeout;
    private int activeToken;

    SoundCuePlayer(Context context) { this.context = context.getApplicationContext(); }

    void play(int resource, String label, Runnable continuation) {
        cancel();
        final int token = gate.begin(continuation);
        activeToken = token;
        try {
            MediaPlayer next = new MediaPlayer();
            player = next;
            next.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
            try (AssetFileDescriptor asset = context.getResources().openRawResourceFd(resource)) {
                if (asset == null) throw new IllegalStateException("Cue resource unavailable");
                next.setDataSource(asset.getFileDescriptor(), asset.getStartOffset(), asset.getLength());
            }
            next.setOnPreparedListener(prepared -> {
                if (player != prepared) return;
                try { prepared.start(); }
                catch (RuntimeException e) { complete(token, "ошибка воспроизведения " + label); }
            });
            next.setOnCompletionListener(ignored -> complete(token, null));
            next.setOnErrorListener((ignored, what, extra) -> {
                complete(token, "ошибка воспроизведения " + label);
                return true;
            });
            timeout = () -> complete(token, "тайм-аут воспроизведения " + label);
            main.postDelayed(timeout, TIMEOUT_MS);
            AppLog.add("Звук: " + label);
            next.prepareAsync();
        } catch (Exception e) {
            complete(token, "не удалось запустить " + label + " (" + e.getClass().getSimpleName() + ")");
        }
    }

    private void complete(int token, String error) {
        if (token != activeToken) return;
        activeToken = 0;
        if (error != null) AppLog.add("Звук: " + error);
        release();
        gate.finish(token);
    }

    void cancel() {
        activeToken = 0;
        gate.cancel();
        release();
    }

    private void release() {
        if (timeout != null) { main.removeCallbacks(timeout); timeout = null; }
        if (player != null) {
            player.setOnPreparedListener(null);
            player.setOnCompletionListener(null);
            player.setOnErrorListener(null);
            player.release();
            player = null;
        }
    }
}
