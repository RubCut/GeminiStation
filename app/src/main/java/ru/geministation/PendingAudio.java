package ru.geministation;

import java.util.ArrayDeque;

/** Bounded pre-connection PCM queue; synchronize on this instance when shared. */
final class PendingAudio {
    private final int limit;
    private final ArrayDeque<byte[]> chunks = new ArrayDeque<>();
    private int bytes;
    PendingAudio(int limit) { if (limit < 0) throw new IllegalArgumentException(); this.limit = limit; }
    boolean offer(byte[] chunk) {
        if (chunk.length > limit - bytes) return false;
        chunks.addLast(chunk); bytes += chunk.length; return true;
    }
    byte[] poll() {
        byte[] chunk = chunks.pollFirst();
        if (chunk != null) bytes -= chunk.length;
        return chunk;
    }
    int sizeBytes() { return bytes; }
    boolean isEmpty() { return chunks.isEmpty(); }
    void clear() { chunks.clear(); bytes = 0; }
}
