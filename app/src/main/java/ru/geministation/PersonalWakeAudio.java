package ru.geministation;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Bounded WAV validation, mono conversion and activity trimming; retains nothing on disk. */
final class PersonalWakeAudio {
    private static final int RATE = 16000, FRAME = 160;
    static final class Clip {
        final short[] pcm; final int first, last;
        Clip(short[] pcm, int first, int last) { this.pcm = pcm; this.first = first; this.last = last; }
    }
    static Clip decode(byte[] wav) throws IOException {
        if (wav == null || wav.length < 44 || wav.length > PersonalWakeStore.MAX_BYTES || wav[0] != 'R' || wav[1] != 'I' || wav[2] != 'F' || wav[3] != 'F')
            throw new IOException("Нужен WAV-файл до 1 МБ");
        PcmAudio audio;
        try { audio = PcmAudio.decode(wav, "audio/wav"); }
        catch (IOException e) { throw new IOException("Нужен WAV PCM 16 бит: моно или стерео, 8–48 кГц", e); }
        int inputLength = audio.samples.length / (2 * audio.channels);
        if (inputLength > audio.rate * 5 || inputLength < audio.rate / 5) throw new IOException("Запись должна длиться от 0,2 до 5 секунд");
        ByteBuffer bytes = ByteBuffer.wrap(audio.samples).order(ByteOrder.LITTLE_ENDIAN);
        float[] mono = new float[inputLength];
        for (int i = 0; i < inputLength; i++) {
            int sum = 0; for (int c = 0; c < audio.channels; c++) sum += bytes.getShort();
            mono[i] = (float) sum / audio.channels;
        }
        int size = (int) ((long) inputLength * RATE / audio.rate);
        short[] pcm = new short[size];
        for (int i = 0; i < size; i++) {
            double pos = (double) i * audio.rate / RATE;
            if (audio.rate > RATE) {
                // Integrate the source interval when downsampling instead of dropping
                // high-frequency samples straight into the lower rate.
                double end = Math.min(inputLength, (double) (i + 1) * audio.rate / RATE), sum = 0;
                for (int j = (int) pos; j < Math.ceil(end); j++) {
                    double weight = Math.min(end, j + 1.0) - Math.max(pos, j);
                    if (weight > 0) sum += mono[j] * weight;
                }
                pcm[i] = (short) Math.round(sum / (end - pos));
            } else {
                int left = Math.min((int) pos, inputLength - 1), right = Math.min(left + 1, inputLength - 1);
                pcm[i] = (short) Math.round(mono[left] + (mono[right] - mono[left]) * (pos - left));
            }
        }
        int blocks = pcm.length / FRAME;
        float[] rms = new float[blocks]; float max = 0;
        for (int block = 0; block < blocks; block++) {
            long sum = 0; for (int i = 0; i < FRAME; i++) { int x = pcm[block * FRAME + i]; sum += (long) x * x; }
            rms[block] = (float) Math.sqrt((double) sum / FRAME); max = Math.max(max, rms[block]);
        }
        if (max < 100) throw new IOException("Запись слишком тихая");
        float gate = Math.max(30, max * .06f); int first = -1, last = -1;
        for (int i = 0; i < rms.length; i++) if (rms[i] > gate) { if (first < 0) first = i; last = i; }
        first = Math.max(0, first - 3); last = Math.min(blocks, last + 4);
        int active = last - first;
        if (active < 20 || active > 130) throw new IOException("Запишите одно слово без длинных пауз: от 0,2 до 1,3 секунды");
        return new Clip(pcm, first, last);
    }
    private PersonalWakeAudio() { }
}
