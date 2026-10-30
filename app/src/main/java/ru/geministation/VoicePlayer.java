package ru.geministation;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import java.io.IOException;
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
    void play(PcmAudio audio, BooleanSupplier active) throws Exception {
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
            int written = 0;
            while (written < audio.samples.length && active.getAsBoolean()) {
                int count = track.write(audio.samples, written, Math.min(4096, audio.samples.length - written));
                if (count <= 0) throw new IOException("Audio playback failed");
                written += count;
            }
            long frames = written / (audio.channels * 2);
            while (active.getAsBoolean() && Integer.toUnsignedLong(track.getPlaybackHeadPosition()) < frames) Thread.sleep(40);
        } finally {
            synchronized (lock) {
                if (current == track) current = null;
                track.release();
            }
        }
    }
}
