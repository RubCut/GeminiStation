package ru.geministation;

/** Stateful 16 kHz frontend. Exactly the 10 ms steps used during model training. */
final class MicroFrontend implements AutoCloseable {
    static { System.loadLibrary("gemini_wake_frontend"); }
    private long handle;
    MicroFrontend() {
        handle = nativeCreate();
        if (handle == 0) throw new IllegalStateException("micro frontend initialization failed");
    }
    int process(short[] pcm, int offset, float[] features) {
        if (handle == 0) throw new IllegalStateException("micro frontend is closed");
        int count = nativeProcess(handle, pcm, offset, features);
        if (count != 0 && count != 40) throw new IllegalStateException("micro frontend processing failed");
        return count;
    }
    @Override public void close() {
        if (handle != 0) { nativeDestroy(handle); handle = 0; }
    }
    private static native long nativeCreate();
    private static native int nativeProcess(long handle, short[] pcm, int offset, float[] output);
    private static native void nativeDestroy(long handle);
}
