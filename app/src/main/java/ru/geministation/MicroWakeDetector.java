package ru.geministation;

import android.content.Context;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import org.tensorflow.lite.DataType;
import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.Tensor;

/** Local, stateful int8 streaming TFLite model. No audio leaves this process. */
final class MicroWakeDetector implements AutoCloseable {
    private static final int FRAME = 160, MEL = 40, STEPS = 3, AVERAGE = 10;
    private final float[] features = new float[MEL], probabilities = new float[AVERAGE];
    private final ByteBuffer input = ByteBuffer.allocateDirect(STEPS * MEL).order(ByteOrder.nativeOrder());
    private final ByteBuffer output = ByteBuffer.allocateDirect(1).order(ByteOrder.nativeOrder());
    private final float threshold, inputScale, outputScale;
    private final int inputZero, outputZero;
    private MicroFrontend frontend;
    private Interpreter interpreter;
    private int frames, historyCount, historyNext;
    private float probabilitySum, lastAverage, intervalPeak;
    private long samplesProcessed, processingNs, maxChunkNs;

    MicroWakeDetector(Context context, String sensitivity) throws IOException {
        threshold = MicroWakePolicy.threshold(sensitivity);
        try {
            ByteBuffer model = load(context);
            frontend = new MicroFrontend();
            interpreter = new Interpreter(model, new Interpreter.Options().setNumThreads(1));
            Tensor in = interpreter.getInputTensor(0), out = interpreter.getOutputTensor(0);
            if (!Arrays.equals(in.shape(), new int[]{1, STEPS, MEL}) || in.dataType() != DataType.INT8 ||
                !Arrays.equals(out.shape(), new int[]{1, 1}) || out.dataType() != DataType.UINT8) {
                throw new IOException("microWakeWord: incompatible model tensors");
            }
            inputScale = in.quantizationParams().getScale(); inputZero = in.quantizationParams().getZeroPoint();
            outputScale = out.quantizationParams().getScale(); outputZero = out.quantizationParams().getZeroPoint();
            if (inputScale <= 0 || outputScale <= 0) throw new IOException("microWakeWord: invalid quantization");
        } catch (IOException | RuntimeException | LinkageError e) {
            close();
            if (e instanceof IOException) throw (IOException) e;
            throw new IOException("microWakeWord initialization failed: " + e.getClass().getSimpleName(), e);
        }
    }
    private static ByteBuffer load(Context context) throws IOException {
        try (InputStream stream = context.getAssets().open("microwakeword/gemini_experimental.tflite");
             ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int count;
            while ((count = stream.read(buffer)) != -1) {
                if (bytes.size() + count > 1024 * 1024) throw new IOException("microWakeWord model too large");
                bytes.write(buffer, 0, count);
            }
            ByteBuffer result = ByteBuffer.allocateDirect(bytes.size()).order(ByteOrder.nativeOrder());
            result.put(bytes.toByteArray()); result.rewind(); return result;
        }
    }
    /** Call with any multiple of 160 samples, on the capture thread only. */
    boolean process(short[] samples, int count) {
        if (count < 0 || count > samples.length || count % FRAME != 0) throw new IllegalArgumentException("PCM frame size");
        long started = System.nanoTime(); boolean hit = false;
        for (int offset = 0; offset < count; offset += FRAME) {
            if (frontend.process(samples, offset, features) == 0) continue;
            for (float feature : features) {
                int q = Math.round(feature / inputScale) + inputZero;
                input.put((byte) Math.max(-128, Math.min(127, q)));
            }
            if (++frames == STEPS) {
                input.rewind(); output.rewind(); interpreter.run(input, output); output.rewind();
                float score = ((output.get() & 0xff) - outputZero) * outputScale;
                if (historyCount == AVERAGE) probabilitySum -= probabilities[historyNext]; else historyCount++;
                probabilities[historyNext] = score; probabilitySum += score; historyNext = (historyNext + 1) % AVERAGE;
                if (historyCount == AVERAGE) {
                    lastAverage = probabilitySum / AVERAGE;
                    intervalPeak = Math.max(intervalPeak, lastAverage);
                    if (lastAverage >= threshold) hit = true;
                }
                frames = 0; input.clear();
            }
        }
        long elapsed = System.nanoTime() - started;
        processingNs += elapsed; maxChunkNs = Math.max(maxChunkNs, elapsed); samplesProcessed += count;
        return hit;
    }
    String diagnostic() {
        String value = String.format(java.util.Locale.US, "пик %.3f, порог %.3f; %s", intervalPeak, threshold, timing());
        intervalPeak = 0;
        return value;
    }
    String timing() {
        // CPU time for frontend+inference, relative to real audio time; excludes AudioRecord waiting.
        long audioMs = samplesProcessed * 1000L / 16000;
        double load = audioMs == 0 ? 0 : processingNs / 1_000_000.0 / audioMs * 100.0;
        return String.format(java.util.Locale.US, "микрофронтенд+модель %.1f%% аудиовремени, максимум %.1f мс/100 мс", load, maxChunkNs / 1_000_000.0);
    }
    @Override public void close() {
        if (interpreter != null) { interpreter.close(); interpreter = null; }
        if (frontend != null) { frontend.close(); frontend = null; }
    }
}
