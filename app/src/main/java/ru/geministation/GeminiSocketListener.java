package ru.geministation;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

/** Gemini Live can send JSON as either text or binary WebSocket frames. */
abstract class GeminiSocketListener extends WebSocketListener {
    private static final int MAX_FRAME_BYTES = 1024 * 1024;
    @Override public final void onMessage(WebSocket socket, String message) {
        if (message.length() > MAX_FRAME_BYTES) { onInvalidFrame("oversized text frame"); return; }
        onJsonFrame(socket, message);
    }
    @Override public final void onMessage(WebSocket socket, ByteString message) {
        if (message.size() > MAX_FRAME_BYTES) { onInvalidFrame("oversized binary frame"); return; }
        try {
            ByteBuffer data = message.asByteBuffer();
            String text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(data).toString();
            onBinaryFrame();
            onJsonFrame(socket, text);
        } catch (CharacterCodingException e) { onInvalidFrame("invalid UTF-8 frame"); }
    }
    protected void onBinaryFrame() { }
    protected abstract void onJsonFrame(WebSocket socket, String message);
    protected abstract void onInvalidFrame(String reason);
}
