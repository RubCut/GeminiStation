package ru.geministation;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.function.BooleanSupplier;

/** Playback is called on a worker; stop() can interrupt it from the UI thread. */
final class VoicePlayer {
    private final Object lock = new Object();
    private AudioTrack current;
    void stop() {
        synchronized (lock) {
            if (current != null) {
                try { current.pause(); current.flush(); } catch (IllegalStateException ignored) { }
            }
        }
    }
    private static long waitForProgress(long stalledAt, BooleanSupplier active) throws IOException {
        if (!active.getAsBoolean()) throw new InterruptedIOException("Playback canceled");
        long now = android.os.SystemClock.elapsedRealtime();
        if (stalledAt == 0) stalledAt = now;
        if (now - stalledAt >= 3000) throw new IOException("Audio output stalled");
        try { Thread.sleep(20); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new InterruptedIOException("Playback interrupted"); }
        return stalledAt;
    }
    Stream openStream(BooleanSupplier active, Runnable onStarted) { return new Stream(active, onStarted); }
    final class Stream implements AutoCloseable {
        private final BooleanSupplier active;
        private final Runnable onStarted;
        private AudioTrack track;
        private int rate, channels;
        private long frames;
        private Stream(BooleanSupplier active, Runnable onStarted) { this.active = active; this.onStarted = onStarted; }
        void write(PcmAudio audio) throws IOException {
            if (!active.getAsBoolean()) throw new InterruptedIOException("Playback canceled");
            if (track == null) {
                rate = audio.rate; channels = audio.channels;
                int channel = channels == 1 ? AudioFormat.CHANNEL_OUT_MONO : AudioFormat.CHANNEL_OUT_STEREO;
                int min = AudioTrack.getMinBufferSize(rate, channel, AudioFormat.ENCODING_PCM_16BIT);
                if (min <= 0) throw new IOException("Audio output unavailable");
                AudioTrack newTrack = new AudioTrack.Builder()
                        .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                        .setAudioFormat(new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setSampleRate(rate).setChannelMask(channel).build())
                        .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(min).build();
                track = newTrack;
                if (newTrack.getState() != AudioTrack.STATE_INITIALIZED) throw new IOException("Audio output unavailable");
                synchronized (lock) {
                    if (!active.getAsBoolean()) throw new InterruptedIOException("Playback canceled");
                    current = newTrack;
                    newTrack.play();
                }
                onStarted.run();
                AppLog.add("TTS: начато потоковое воспроизведение");
            }
            if (rate != audio.rate || channels != audio.channels) throw new IOException("Audio format changed mid-stream");
            int offset = 0; long stalledAt = 0;
            while (offset < audio.samples.length && active.getAsBoolean()) {
                int count = track.write(audio.samples, offset, Math.min(4096, audio.samples.length - offset));
                if (count < 0) throw new IOException("Audio playback failed (" + count + ")");
                if (count == 0) { stalledAt = waitForProgress(stalledAt, active); continue; }
                stalledAt = 0; offset += count;
            }
            if (!active.getAsBoolean()) throw new InterruptedIOException("Playback canceled");
            frames += offset / (2 * channels);
        }
        void drain() throws Exception {
            if (track == null) throw new IOException("No audio to play");
            long deadline = android.os.SystemClock.elapsedRealtime() + frames * 1000 / rate + 5000;
            while (active.getAsBoolean() && Integer.toUnsignedLong(track.getPlaybackHeadPosition()) < frames) {
                if (android.os.SystemClock.elapsedRealtime() >= deadline) throw new IOException("Audio playback timeout");
                Thread.sleep(40);
            }
        }
        @Override public void close() {
            synchronized (lock) {
                if (track != null) {
                    if (current == track) current = null;
                    track.release(); track = null;
                }
            }
        }
    }
    void play(PcmAudio audio, BooleanSupplier active, Runnable onStarted) throws Exception {
        int channel = audio.channels == 1 ? AudioFormat.CHANNEL_OUT_MONO : AudioFormat.CHANNEL_OUT_STEREO;
        int minBuffer = AudioTrack.getMinBufferSize(audio.rate, channel, AudioFormat.ENCODING_PCM_16BIT);
        if (minBuffer <= 0) throw new IOException("Audio output unavailable");
        AudioTrack track = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(audio.rate).setChannelMask(channel).build())
                .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(minBuffer).build();
        try {
            if (track.getState() != AudioTrack.STATE_INITIALIZED) throw new IOException("Audio output unavailable");
            synchronized (lock) {
                if (!active.getAsBoolean()) return;
                current = track;
                track.play();
            }
            onStarted.run();
            int written = 0; long stalledAt = 0;
            while (written < audio.samples.length && active.getAsBoolean()) {
                int count = track.write(audio.samples, written, Math.min(4096, audio.samples.length - written));
                if (count < 0) throw new IOException("Audio playback failed (" + count + ")");
                if (count == 0) { stalledAt = waitForProgress(stalledAt, active); continue; }
                stalledAt = 0; written += count;
            }
            long frames = written / (audio.channels * 2);
            long deadline = android.os.SystemClock.elapsedRealtime() + frames * 1000 / audio.rate + 5000;
            while (active.getAsBoolean() && Integer.toUnsignedLong(track.getPlaybackHeadPosition()) < frames) {
                if (android.os.SystemClock.elapsedRealtime() >= deadline) throw new IOException("Audio playback timeout");
                Thread.sleep(40);
            }
        } finally {
            synchronized (lock) {
                if (current == track) current = null;
                track.release();
            }
        }
    }
}
