package ru.geministation;

import java.io.DataInputStream;
import java.io.DataOutputStream;

/** Root process with exactly one permitted framebuffer and fixed 24-pixel frames. */
public final class RootRingHelper {
    private static native long openRing();
    private static native boolean writeFrame(long handle, byte[] rgb);
    private static native void closeRing(long handle);
    public static void main(String[] args) {
        long handle = 0;
        try (DataInputStream in = new DataInputStream(System.in); DataOutputStream out = new DataOutputStream(System.out)) {
            if (args.length != 1) return;
            System.load(args[0]);
            handle = openRing();
            out.writeByte(handle <= 0 ? (int) -handle : 0); out.flush();
            if (handle <= 0) { handle = 0; return; }
            byte[] frame = new byte[RingAnimator.FRAME_BYTES];
            for (;;) {
                in.readFully(frame);
                boolean success = writeFrame(handle, frame);
                out.writeByte(success ? 0 : 1); out.flush();
                if (!success) return;
            }
        } catch (Throwable ignored) {
            // Parent closes the pipe when the conversation stops.
        } finally { if (handle != 0) closeRing(handle); }
    }
    private RootRingHelper() { }
}
