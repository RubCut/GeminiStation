package ru.geministation;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Locale;

/** Gemini TTS can return a WAV container or headerless 16-bit PCM. */
final class PcmAudio {
    final byte[] samples;
    final int rate;
    final int channels;
    private PcmAudio(byte[] samples, int rate, int channels) {
        this.samples = samples; this.rate = rate; this.channels = channels;
    }
    static PcmAudio decode(byte[] bytes, String mime) throws IOException {
        if (bytes.length == 0 || bytes.length > 8 * 1024 * 1024) throw new IOException("Invalid audio size");
        if (bytes.length >= 12 && tag(bytes, 0, "RIFF") && tag(bytes, 8, "WAVE")) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            int cursor = 12, rate = 0, channels = 0, bits = 0, format = 0, dataStart = -1, dataLength = 0;
            while (cursor <= bytes.length - 8) {
                long length = Integer.toUnsignedLong(buffer.getInt(cursor + 4));
                long next = (long) cursor + 8 + length + (length & 1);
                if (next > bytes.length) throw new IOException("Truncated WAV chunk");
                if (tag(bytes, cursor, "fmt ")) {
                    if (length < 16) throw new IOException("Invalid WAV format");
                    format = Short.toUnsignedInt(buffer.getShort(cursor + 8));
                    channels = Short.toUnsignedInt(buffer.getShort(cursor + 10));
                    rate = buffer.getInt(cursor + 12);
                    bits = Short.toUnsignedInt(buffer.getShort(cursor + 22));
                } else if (tag(bytes, cursor, "data")) {
                    dataStart = cursor + 8; dataLength = (int) length;
                }
                cursor = (int) next;
            }
            if (format != 1 || bits != 16 || channels < 1 || channels > 2 || rate < 8000 || rate > 48000 || dataStart < 0 || dataLength == 0 || dataLength % (2 * channels) != 0)
                throw new IOException("Unsupported WAV format");
            return new PcmAudio(Arrays.copyOfRange(bytes, dataStart, dataStart + dataLength), rate, channels);
        }
        String type = mime == null ? "" : mime.toLowerCase(Locale.ROOT);
        if (!(type.startsWith("audio/l16") || type.startsWith("audio/pcm")) || bytes.length % 2 != 0)
            throw new IOException("Unsupported audio format");
        int rate = 24000;
        for (String param : type.split(";")) {
            String trimmed = param.trim();
            if (trimmed.startsWith("rate=")) {
                try { rate = Integer.parseInt(trimmed.substring(5)); }
                catch (NumberFormatException e) { throw new IOException("Invalid sample rate"); }
            }
        }
        if (rate < 8000 || rate > 48000) throw new IOException("Unsupported sample rate");
        return new PcmAudio(bytes, rate, 1);
    }
    /** Wrap decoded PCM as standard WAV for browser playback. */
    byte[] toWav() {
        ByteBuffer out = ByteBuffer.allocate(44 + samples.length).order(ByteOrder.LITTLE_ENDIAN);
        out.put(new byte[]{'R','I','F','F'}).putInt(36 + samples.length).put(new byte[]{'W','A','V','E'});
        out.put(new byte[]{'f','m','t',' '}).putInt(16).putShort((short) 1).putShort((short) channels);
        out.putInt(rate).putInt(rate * channels * 2).putShort((short) (channels * 2)).putShort((short) 16);
        out.put(new byte[]{'d','a','t','a'}).putInt(samples.length).put(samples);
        return out.array();
    }
    private static boolean tag(byte[] bytes, int start, String tag) {
        for (int i = 0; i < tag.length(); i++) if (bytes[start + i] != (byte) tag.charAt(i)) return false;
        return true;
    }
}
