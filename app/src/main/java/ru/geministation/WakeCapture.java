package ru.geministation;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.SystemClock;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Owns one microphone and local microWakeWord detector on the capture thread. */
final class WakeCapture {
    interface Listener {
        void onLevel(WakeCapture owner, int rms, int peak);
        void onTrigger(WakeCapture owner);
        void onFinished(WakeCapture owner, String error);
    }
    private static final int RATE = 16000, CHUNK = 1600;
    private final Listener listener;
    private final String sensitivity, engine;
    private final Context context;
    private final boolean filter, amplify, vad, personalEnabled;
    private final VoiceTuning tuning;
    private final AtomicBoolean stopping = new AtomicBoolean();
    private volatile AudioRecord recorder;
    private MicroWakeDetector micro;
    private PersonalWakeWorker personal;
    private Thread thread;
    private String sourceName;
    private long recordingStarted;

    WakeCapture(Context context, Listener listener, String engine, String sensitivity, boolean filter, boolean amplify, boolean vad, boolean personalEnabled, VoiceTuning tuning) {
        this.context = context; this.listener = listener; this.engine = engine; this.sensitivity = sensitivity;
        this.filter = filter; this.amplify = amplify; this.vad = vad; this.personalEnabled = personalEnabled; this.tuning = tuning;
    }
    boolean sameSettings(String selectedEngine, String value, boolean filterEnabled, boolean amplifyEnabled, boolean vadEnabled, boolean personalIsEnabled, VoiceTuning other) {
        return engine.equals(selectedEngine) && sensitivity.equals(value) && filter == filterEnabled && amplify == amplifyEnabled && vad == vadEnabled && personalEnabled == personalIsEnabled && tuning.sameWake(other);
    }
    String sensitivity() { return sensitivity; }
    String sourceName() { return sourceName; }
    boolean isAlive() { return thread != null && thread.isAlive(); }

    void start() throws IOException {
        try {
            try {
                java.util.List<float[][]> templates = personalEnabled ? PersonalWakeStore.load(context) : java.util.Collections.emptyList();
                if (!templates.isEmpty()) personal = new PersonalWakeWorker(templates);
            } catch (Exception e) {
                AppLog.add("Образцы произношения недоступны; общая модель продолжает работать");
            }
            micro = new MicroWakeDetector(context, sensitivity, engine, vad, personal);
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
            recordingStarted = SystemClock.elapsedRealtime();
            if (recorder.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) throw new IOException("Cannot start microphone");
            thread = new Thread(this::capture, "GeminiWakeMicrophone");
            thread.start();
        } catch (Exception | LinkageError e) {
            cleanup();
            if (e instanceof IOException) throw (IOException) e;
            throw new IOException("Wake-word initialization failed: " + e.getClass().getSimpleName(), e);
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
            long lastLevel = SystemClock.elapsedRealtime(), lastTiming = lastLevel;
            int buffered = 0;
            long samplesRead = 0;
            while (!stopping.get()) {
                int count = recorder.read(samples, buffered, samples.length - buffered);
                if (stopping.get()) break;
                if (count < 0) throw new IOException("AudioRecord.read=" + count);
                if (count == 0) { Thread.sleep(20); continue; }
                buffered += count; samplesRead += count;
                if (buffered < samples.length) continue;
                count = buffered; buffered = 0;
                level.add(samples, count);
                conditioning.process(samples, count, amplify);
                boolean microHit = micro.process(samples, count);
                long now = SystemClock.elapsedRealtime();
                if (personal != null && conditioning.speechLike() && micro.speechRecently()) personal.offer(now);
                boolean personalHit = personal != null && personal.takeHit(now);
                if (now - lastLevel >= 2000) {
                    listener.onLevel(this, level.rms(), level.peak());
                    level.reset(); lastLevel = now;
                }
                if (micro != null && now - lastTiming >= 20000) {
                    long elapsedAudioMs = samplesRead * 1000L / RATE;
                    long differenceMs = Math.max(0, now - recordingStarted - elapsedAudioMs);
                    AppLog.add("microWakeWord за последние 20 с: " + micro.diagnostic()
                            + "; разница времени записи и прочитанного звука " + differenceMs + " мс"); lastTiming = now;
                }
                if (microHit || personalHit) {
                    int recentMs = personalHit ? Math.max(tuning.wakeRecentMs, 600) : tuning.wakeRecentMs;
                    if (conditioning.candidateAllowed(filter, tuning.wakeMinMs, recentMs)) {
                        AppLog.add("Голосовая активация: кандидат принят (" + (microHit ? "общая модель" : "личный образец") + "); сигнал " + conditioning.activeMillis()
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
                    // A rejected personal match must not change the general detector's signal history.
                    if (microHit) conditioning.clearSpeech();
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
        if (micro != null) { micro.close(); micro = null; }
        if (personal != null) { personal.close(); personal = null; }
    }
}
