package ru.geministation;

/** Serializes pre-connection audio and end-of-input. No audio is written to disk. */
final class AudioStreamBuffer {
    interface Sink {
        void audio(byte[] pcm) throws Exception;
        void end() throws Exception;
    }
    private final PendingAudio pending;
    private boolean connected, finished, cancelled, endSent;
    AudioStreamBuffer(int maxBytes) { pending = new PendingAudio(maxBytes); }
    synchronized boolean write(byte[] pcm, Sink sink) throws Exception {
        if (cancelled || finished) return false;
        if (connected) { sink.audio(pcm); return true; }
        return pending.offer(pcm);
    }
    synchronized int connect(Sink sink) throws Exception {
        if (cancelled || connected) return 0;
        connected = true;
        int bytes = pending.sizeBytes();
        while (!pending.isEmpty()) sink.audio(pending.poll());
        sendEndIfFinished(sink);
        return bytes;
    }
    synchronized void finish(Sink sink) throws Exception {
        if (cancelled || finished) return;
        finished = true;
        if (connected) sendEndIfFinished(sink);
    }
    private void sendEndIfFinished(Sink sink) throws Exception {
        if (finished && !endSent) { sink.end(); endSent = true; }
    }
    synchronized void cancel() { cancelled = true; pending.clear(); }
}
