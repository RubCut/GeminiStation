package ru.geministation;

import java.util.Arrays;

/** Signal-energy guard for Live transcription timers; not a speech classifier. */
final class AdaptiveSignal {
    private final int[] history = new int[40];
    private int count, next;
    private double floor = 40;
    boolean accept(int rms) {
        history[next] = Math.max(0, rms); next = (next + 1) % history.length; count = Math.min(count + 1, history.length);
        if (count >= 5) {
            int[] sorted = Arrays.copyOf(history, count); Arrays.sort(sorted);
            int target = sorted[(count - 1) / 5];
            // Learn stationary background slowly; lower the floor quickly after a loud environment.
            floor += (target - floor) * (target > floor ? 0.01 : 0.15);
        }
        return rms >= Math.max(80, floor * 1.5);
    }
    int floor() { return (int) Math.round(floor); }
}
