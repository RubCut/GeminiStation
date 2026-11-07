package ru.geministation;

/** Deterministic ring animation. Frame data is RGB, 24 pixels; no device access. */
final class RingAnimator {
    static final int PIXELS = 24, FRAME_BYTES = PIXELS * 3;
    private static final double FRONT = -.5;
    private static final double[] POINTS = {FRONT, 5, 19, 9, 23, 3, 14, 6, 20, 11, FRONT};
    private static final int[] TRAVEL = {220, 330, 260, 270, 300, 320, 250, 300, 290, 340};
    private static final int[] HOLD = {100, 70, 90, 80, 110, 60, 80, 100, 70, 80};
    private static final int LISTEN_CYCLE;
    static { int sum = 0; for (int n : TRAVEL) sum += n; for (int n : HOLD) sum += n; LISTEN_CYCLE = sum; }
    private String phase = "activation";
    private long started;
    private double from = FRONT;

    static boolean validPhase(String phase) {
        return "activation".equals(phase) || "listening".equals(phase) || "thinking".equals(phase) || "speaking".equals(phase);
    }
    RingAnimator(long now) { started = now; }
    void setPhase(String next, long now) {
        if (!validPhase(next)) throw new IllegalArgumentException("phase");
        if (next.equals(phase)) return;
        from = center(now); phase = next; started = now;
    }
    private static double smooth(double x) { x = Math.max(0, Math.min(1, x)); return x*x*(3-2*x); }
    private static double shortest(double from, double to) {
        double d = (to - from) % PIXELS;
        if (d > 12) d -= PIXELS;
        if (d < -12) d += PIXELS;
        return d;
    }
    private static double listening(long time) {
        long t = time % LISTEN_CYCLE;
        for (int i = 0; i < TRAVEL.length; i++) {
            double delta = shortest(POINTS[i], POINTS[i+1]);
            if (t < TRAVEL[i]) return POINTS[i] + delta * smooth((double) t / TRAVEL[i]);
            t -= TRAVEL[i];
            if (t < HOLD[i]) return POINTS[i+1];
            t -= HOLD[i];
        }
        return FRONT;
    }
    double center(long now) {
        long t = Math.max(0, now - started);
        switch (phase) {
            case "activation": return FRONT + 24 * smooth((t - 100.0) / (2080.0 / 3));
            case "thinking": return from + 24.0 * t / 2500.0;
            case "speaking": return from + shortest(from, FRONT) * smooth(t / 550.0);
            default:
                if (t < 150) return from + shortest(from, FRONT) * smooth(t / 150.0);
                return listening(t - 150);
        }
    }
    byte[] frame(long now, boolean monochrome) {
        long t = Math.max(0, now - started);
        double center = center(now), width = 3.8, alpha = 1;
        if ("activation".equals(phase)) alpha = smooth(t / 150.0);
        if ("listening".equals(phase)) width = 3.5;
        if ("speaking".equals(phase) && t >= 550) width = 3.0 + 1.9 * (.5 - .5 * Math.cos(2 * Math.PI * (t - 550) / 1050));
        byte[] frame = new byte[FRAME_BYTES];
        for (int i = 0; i < PIXELS; i++) {
            double d = Math.abs(shortest(center, i));
            if (d >= width) continue;
            double blend = smooth(Math.min(1, d / Math.max(1, width * .66)));
            double energy = smooth((width - d) / Math.max(1, width * .72)) * alpha;
            int[] blue = {94,217,255}, pink = {255,102,191};
            for (int channel = 0; channel < 3; channel++) {
                double value = monochrome ? 255 : blue[channel] * (1 - blend) + pink[channel] * blend;
                frame[i * 3 + channel] = (byte) Math.round(value * energy);
            }
        }
        return frame;
    }
}
