package ru.geministation;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.SystemClock;
import edu.cmu.pocketsphinx.Config;
import edu.cmu.pocketsphinx.Decoder;
import edu.cmu.pocketsphinx.Hypothesis;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

/** Owns one microphone and decoder; only the capture thread uses native decoder calls after start. */
final class WakeCapture {
    interface Listener {
        void onLevel(WakeCapture owner, int rms, int peak);
        void onTrigger(WakeCapture owner);
        void onFinished(WakeCapture owner, String error);
    }
    static { System.loadLibrary("pocketsphinx_jni"); }
    private static final int RATE = 16000, CHUNK = 1600;
    private final Listener listener;
    private final String sensitivity;
    private final boolean filter, amplify;
    private final VoiceTuning tuning;
    private final AtomicBoolean stopping = new AtomicBoolean();
    private volatile AudioRecord recorder;
    private Decoder decoder;
    private Thread thread;
    private String sourceName;
    private boolean utteranceStarted;

    WakeCapture(Listener listener, String sensitivity, boolean filter, boolean amplify, VoiceTuning tuning) {
        this.listener = listener; this.sensitivity = sensitivity; this.filter = filter; this.amplify = amplify; this.tuning = tuning;
    }
    boolean sameSettings(String value, boolean filterEnabled, boolean amplifyEnabled, VoiceTuning other) {
        return sensitivity.equals(value) && filter == filterEnabled && amplify == amplifyEnabled && tuning.sameWake(other);
    }
    String sensitivity() { return sensitivity; }
    String sourceName() { return sourceName; }
    boolean isAlive() { return thread != null && thread.isAlive(); }

    void start(File model) throws IOException {
        Config config = null;
        try {
            config = Decoder.defaultConfig();
            config.setString("-hmm", model.getAbsolutePath());
            config.setString("-dict", new File(model, "words.dict").getAbsolutePath());
            config.setString("-fdict", new File(model, "noisedict").getAbsolutePath());
            config.setString("-lda", new File(model, "feature_transform").getAbsolutePath());
            config.setFloat("-samprate", RATE);
            config.setBoolean("-backtrace", false);
            decoder = new Decoder(config);
            File keywords = new File(model, "active-keywords.list");
            try (FileOutputStream out = new FileOutputStream(keywords)) {
                out.write(WakeSensitivity.keywords(sensitivity).getBytes(StandardCharsets.UTF_8));
            }
            decoder.setKws("gemini-wake", keywords.getAbsolutePath());
            decoder.setSearch("gemini-wake");
            int minimum = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (minimum <= 0) throw new IOException("16 kHz audio is not supported");
            recorder = create(MediaRecorder.AudioSource.MIC, Math.max(minimum, CHUNK * 4));
            sourceName = "MIC";
            if (recorder == null) {
                recorder = create(MediaRecorder.AudioSource.VOICE_RECOGNITION, Math.max(minimum, CHUNK * 4));
                sourceName = "VOICE_RECOGNITION";
            }
            if (recorder == null) throw new IOException("Cannot initialize microphone");
            recorder.startRecording();
            if (recorder.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) throw new IOException("Cannot start microphone");
            decoder.startUtt();
            utteranceStarted = true;
            thread = new Thread(this::capture, "GeminiWakeMicrophone");
            thread.start();
        } catch (Exception | LinkageError e) {
            cleanup();
            if (e instanceof IOException) throw (IOException) e;
            throw new IOException("Wake-word initialization failed: " + e.getClass().getSimpleName(), e);
        } finally {
            if (config != null) config.delete();
        }
    }
    private static AudioRecord create(int source, int size) {
        try {
            AudioRecord result = new AudioRecord(source, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, size);
            if (result.getState() == AudioRecord.STATE_INITIALIZED) return result;
            result.release();
        } catch (IllegalArgumentException | SecurityException ignored) { }
        return null;
    }
    private void capture() {
        String error = null;
        try {
            short[] samples = new short[CHUNK];
            AudioLevel level = new AudioLevel();
            WakeAudioFilter conditioning = new WakeAudioFilter();
            long lastRejectedLog = 0;
            long lastLevel = SystemClock.elapsedRealtime();
            int buffered = 0;
            while (!stopping.get()) {
                int count = recorder.read(samples, buffered, samples.length - buffered);
                if (stopping.get()) break;
                if (count < 0) throw new IOException("AudioRecord.read=" + count);
                if (count == 0) { Thread.sleep(20); continue; }
                buffered += count;
                if (buffered < samples.length) continue;
                count = buffered; buffered = 0;
                level.add(samples, count);
                conditioning.process(samples, count, amplify);
                decoder.processRaw(samples, count, false, false);
                long now = SystemClock.elapsedRealtime();
                if (now - lastLevel >= 2000) {
                    listener.onLevel(this, level.rms(), level.peak());
                    level.reset(); lastLevel = now;
                }
                Hypothesis result = decoder.hyp();
                if (result != null && WakeWordResult.matches(result.getHypstr())) {
                    if (conditioning.candidateAllowed(filter, tuning.wakeMinMs, tuning.wakeRecentMs)) {
                        AppLog.add("Голосовая активация: кандидат принят; сигнал " + conditioning.activeMillis()
                                + " мс, пауза " + conditioning.quietMillis() + " мс, RMS " + conditioning.rms()
                                + ", фон " + conditioning.floor() + ", фильтр " + filter);
                        listener.onTrigger(this);
                        break;
                    }
                    if (now - lastRejectedLog >= 10000) {
                        AppLog.add("Голосовая активация: кандидат отклонён; сигнал " + conditioning.activeMillis()
                                + " мс, пауза " + conditioning.quietMillis() + " мс, RMS " + conditioning.rms()
                                + ", фон " + conditioning.floor() + ", фильтр " + filter
                                + ", усиление " + conditioning.gainPercent() + "%");
                        lastRejectedLog = now;
                    }
                    // KWS hypotheses can persist for the whole utterance; discard the rejected event.
                    decoder.endUtt(); decoder.startUtt(); conditioning.clearSpeech();
                } else if (conditioning.resetForSilence(tuning.wakeResetMs)) {
                    // KWS searches are continuous and can otherwise retain old detections.
                    decoder.endUtt(); decoder.startUtt(); conditioning.clearSpeech();
                }
            }
        } catch (Exception | LinkageError e) {
            if (!stopping.get()) error = e.getClass().getSimpleName() + (e instanceof IOException ? ": " + e.getMessage() : "");
        } finally {
            cleanup();
            listener.onFinished(this, error);
        }
    }
    /** Stop and wait for the capture thread to release AudioRecord before handing it to Gemini Live. */
    boolean stopAndWait() {
        stopping.set(true);
        AudioRecord current = recorder;
        if (current != null) try { current.stop(); } catch (IllegalStateException ignored) { }
        if (thread != null && thread != Thread.currentThread()) {
            try { thread.join(4000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
        }
        return !isAlive();
    }
    private void cleanup() {
        if (recorder != null) {
            try { recorder.stop(); } catch (IllegalStateException ignored) { }
            recorder.release(); recorder = null;
        }
        if (decoder != null) {
            if (utteranceStarted) try { decoder.endUtt(); } catch (RuntimeException ignored) { }
            decoder.delete(); decoder = null;
        }
    }
}
