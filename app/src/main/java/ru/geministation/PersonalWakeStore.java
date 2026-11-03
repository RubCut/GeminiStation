package ru.geministation;

import android.content.Context;
import android.util.Base64;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Enrolment is local. Store only encrypted spectral templates, never uploaded WAVs. */
final class PersonalWakeStore {
    static final int MAX_BYTES = 1_000_000;
    private static final int FRAME = 160;
    private static SecretStore store(Context context) { return new SecretStore(context, "personal_wake_samples"); }

    static synchronized List<float[][]> load(Context context) throws Exception {
        String value = store(context).load();
        List<float[][]> result = new ArrayList<>();
        if (value.isEmpty()) return result;
        JSONObject root = new JSONObject(value);
        if (root.getInt("version") != 1) throw new IOException("Unknown personal sample format");
        JSONArray items = root.getJSONArray("templates");
        if (items.length() > PersonalWakeMatcher.MAX_TEMPLATES) throw new IOException("Too many samples");
        for (int i = 0; i < items.length(); i++) {
            byte[] bytes = Base64.decode(items.getString(i), Base64.NO_WRAP);
            int frameBytes = PersonalWakeMatcher.BANDS * 4;
            if (bytes.length % frameBytes != 0 || bytes.length / frameBytes < 10 || bytes.length / frameBytes > 65)
                throw new IOException("Invalid personal sample");
            ByteBuffer input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            float[][] frames = new float[bytes.length / frameBytes][PersonalWakeMatcher.BANDS];
            for (float[] frame : frames) for (int j = 0; j < frame.length; j++) {
                frame[j] = input.getFloat();
                if (Float.isNaN(frame[j]) || Float.isInfinite(frame[j]) || Math.abs(frame[j]) > 10) throw new IOException("Invalid personal feature");
            }
            result.add(frames);
        }
        return result;
    }
    static synchronized int count(Context context) throws Exception { return load(context).size(); }
    static synchronized void clear(Context context) { store(context).clear(); }
    static synchronized int add(Context context, byte[] wav) throws Exception {
        List<float[][]> existing = load(context);
        if (existing.size() >= PersonalWakeMatcher.MAX_TEMPLATES) throw new IOException("Можно сохранить не более пяти образцов");
        float[][] features = extract(wav);
        existing.add(features);
        JSONArray items = new JSONArray();
        for (float[][] sample : existing) {
            ByteBuffer bytes = ByteBuffer.allocate(sample.length * PersonalWakeMatcher.BANDS * 4).order(ByteOrder.LITTLE_ENDIAN);
            for (float[] frame : sample) for (float value : frame) bytes.putFloat(value);
            items.put(Base64.encodeToString(bytes.array(), Base64.NO_WRAP));
        }
        store(context).save(new JSONObject().put("version", 1).put("templates", items).toString());
        return existing.size();
    }
    static float[][] extract(byte[] wav) throws IOException {
        PersonalWakeAudio.Clip clip = PersonalWakeAudio.decode(wav);
        short[] pcm = clip.pcm;
        int blocks = pcm.length / FRAME, first = clip.first, last = clip.last;
        List<float[]> frames = new ArrayList<>();
        short[] silence = new short[FRAME]; float[] values = new float[40];
        try (MicroFrontend frontend = new MicroFrontend()) {
            for (int i = 0; i < 200; i++) frontend.process(silence, 0, values);
            for (int i = 0; i < blocks; i++) {
                if (frontend.process(pcm, i * FRAME, values) == 40 && i >= first && i < last && ((i - first) & 1) == 0)
                    frames.add(PersonalWakeMatcher.normalize(values));
            }
        } catch (RuntimeException | LinkageError e) { throw new IOException("Не удалось обработать аудио", e); }
        if (frames.size() < 10 || frames.size() > 65) throw new IOException("Не удалось выделить слово");
        return frames.toArray(new float[0][]);
    }
    private PersonalWakeStore() { }
}
