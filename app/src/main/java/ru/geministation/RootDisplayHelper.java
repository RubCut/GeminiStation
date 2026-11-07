package ru.geministation;

import java.io.DataInputStream;
import java.io.DataOutputStream;

/** Launched via app_process as root. Its only operation is writing fixed-size framebuffer frames. */
public final class RootDisplayHelper {
    private static native long openDisplay();
    private static native boolean writeFrame(long handle, byte[] rgb);
    private static native void closeDisplay(long handle);
    public static void main(String[] args) {
        long handle = 0;
        try (DataInputStream in = new DataInputStream(System.in); DataOutputStream out = new DataOutputStream(System.out)) {
            if (args.length != 1) return;
            System.load(args[0]);
            handle = openDisplay();
            out.writeByte(handle <= 0 ? (int) -handle : 0); out.flush();
            if (handle <= 0) { handle = 0; return; }
            byte[] frame = new byte[25 * 16 * 3];
            for (;;) {
                in.readFully(frame);
                boolean success = writeFrame(handle, frame);
                out.writeByte(success ? 0 : 1); out.flush();
                if (!success) return;
            }
        } catch (Throwable ignored) {
            // EOF means that the unprivileged parent has closed the session.
        } finally { if (handle != 0) closeDisplay(handle); }
    }
    private RootDisplayHelper() { }
}
