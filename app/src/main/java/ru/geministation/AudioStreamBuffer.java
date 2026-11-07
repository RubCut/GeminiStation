package ru.geministation;

/** Serializes pre-connection audio and end-of-input. No audio is written to disk. */
final class AudioStreamBuffer {
    interface Sink {
        void audio(byte[] pcm) throws Exception;
        void end() throws Exception;
    }
    private final PendingAudio pending;
    private boolean connected, draining, finished, cancelled, endSent;
    AudioStreamBuffer(int maxBytes) { pending = new PendingAudio(maxBytes); }
    synchronized boolean write(byte[] pcm, Sink sink) throws Exception {
        if (cancelled || finished) return false;
        if (connected && !draining) { sink.audio(pcm); return true; }
        return pending.offer(pcm);
    }
    int connect(Sink sink) throws Exception {
        final int bytes;
        synchronized (this) {
            if (cancelled || connected) return 0;
            connected = true; draining = true; bytes = pending.sizeBytes();
        }
        // Keep capture non-blocking while the pre-connect queue drains, without
        // letting new chunks overtake it. The same bounded queue limits memory.
        for (;;) {
            byte[] chunk;
            synchronized (this) {
                if (cancelled) { draining = false; return bytes; }
                chunk = pending.poll();
                if (chunk == null) {
                    draining = false;
                    sendEndIfFinished(sink);
                    return bytes;
                }
            }
            sink.audio(chunk);
        }
    }
    synchronized void finish(Sink sink) throws Exception {
        if (cancelled || finished) return;
        finished = true;
        if (connected && !draining) sendEndIfFinished(sink);
    }
    private void sendEndIfFinished(Sink sink) throws Exception {
        if (finished && !endSent) { sink.end(); endSent = true; }
    }
    synchronized void cancel() { cancelled = true; pending.clear(); }
}
