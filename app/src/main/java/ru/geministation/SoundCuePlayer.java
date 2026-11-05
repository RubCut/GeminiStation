package ru.geministation;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Local UI-thread cues. The continuation runs after completion, error, or timeout, never after cancel. */
final class SoundCuePlayer {
    private static final long TIMEOUT_MS = 5000;
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final CueGate gate = new CueGate();
    // The confirmed-working speech output path also warms up the audio route for the short activation cue.
    private final ExecutorService cueWorker = Executors.newSingleThreadExecutor();
    private final VoicePlayer cueVoice = new VoicePlayer();
    private AtomicBoolean activationPlaying;
    private MediaPlayer player;
    private Runnable timeout;
    private int activeToken;
    private long wakeDetectedAt;
    void setWakeDetectedAt(long value) { wakeDetectedAt = value; }

    SoundCuePlayer(Context context) { this.context = context.getApplicationContext(); }

    void play(int resource, String label, Runnable continuation) {
        cancel();
        final int token = gate.begin(continuation);
        activeToken = token;
        timeout = () -> complete(token, "тайм-аут воспроизведения " + label);
        main.postDelayed(timeout, TIMEOUT_MS);
        if (resource == R.raw.assistant_start) {
            playActivation(resource, label, token);
        } else {
            playMedia(resource, label, token);
        }
    }

    private void playActivation(int resource, String label, int token) {
        AtomicBoolean active = new AtomicBoolean(true);
        activationPlaying = active;
        AppLog.add("Звук: активация — подготовка аудиовыхода");
        cueWorker.execute(() -> {
            try {
                byte[] wav;
                try (InputStream in = context.getResources().openRawResource(resource);
                     ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                    byte[] buf = new byte[4096];
                    int count;
                    while ((count = in.read(buf)) != -1) {
                        if (out.size() + count > 1024 * 1024) throw new java.io.IOException("Cue too large");
                        out.write(buf, 0, count);
                    }
                    wav = out.toByteArray();
                }
                // Some TV audio routes need time to wake from standby; never alter the supplied cue samples.
                PcmAudio cue = PcmAudio.decode(wav, "audio/wav").withSilence(350, 150);
                cueVoice.play(cue, active::get, () -> AppLog.add("Звук: активация — AudioTrack запущен"
                        + (wakeDetectedAt > 0 ? " через " + (SystemClock.elapsedRealtime() - wakeDetectedAt)
                                + " мс после обнаружения (плюс 350 мс тишины для прогрева)" : "")));
                if (active.get()) main.post(() -> complete(token, null));
            } catch (Exception e) {
                if (active.get()) main.post(() -> {
                    if (token != activeToken) return;
                    AppLog.add("Звук: AudioTrack недоступен (" + e.getClass().getSimpleName() + "), пробуем MediaPlayer");
                    playMedia(resource, label, token);
                });
            }
        });
    }

    private void playMedia(int resource, String label, int token) {
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
                try { prepared.start(); AppLog.add("Звук: " + label + " — MediaPlayer запущен"); }
                catch (RuntimeException e) { complete(token, "ошибка воспроизведения " + label); }
            });
            next.setOnCompletionListener(ignored -> complete(token, null));
            next.setOnErrorListener((ignored, what, extra) -> {
                complete(token, "ошибка воспроизведения " + label + " (" + what + ", " + extra + ")");
                return true;
            });
            AppLog.add("Звук: " + label + " — подготовка MediaPlayer");
            next.prepareAsync();
        } catch (Exception e) {
            complete(token, "не удалось запустить " + label + " (" + e.getClass().getSimpleName() + ")");
        }
    }

    private void complete(int token, String error) {
        if (token != activeToken) return;
        activeToken = 0;
        if (error != null) AppLog.add("Звук: " + error);
        else AppLog.add("Звук: воспроизведение завершено");
        release();
        gate.finish(token);
    }

    void cancel() {
        activeToken = 0;
        gate.cancel();
        release();
    }

    void close() { cancel(); cueWorker.shutdownNow(); }

    private void release() {
        if (activationPlaying != null) {
            activationPlaying.set(false); activationPlaying = null; cueVoice.stop();
        }
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
