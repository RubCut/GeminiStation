package ru.geministation;

import java.io.IOException;
import java.io.Reader;

/** Bounded SSE parser, independent of Android for unit testing. */
final class SseReader {
    private static final int MAX_LINE = 1024 * 1024;
    private static final int MAX_EVENT = 2 * 1024 * 1024;
    private final Reader reader;
    private boolean eof;
    SseReader(Reader reader) { this.reader = reader; }
    String next() throws IOException {
        StringBuilder data = new StringBuilder();
        boolean hasData = false;
        for (;;) {
            String line = readLine();
            if (line == null || line.isEmpty()) {
                if (hasData) return data.toString();
                if (line == null) return null;
                continue;
            }
            if (line.startsWith(":")) continue;
            int colon = line.indexOf(':');
            String field = colon < 0 ? line : line.substring(0, colon);
            if (!"data".equals(field)) continue;
            String value = colon < 0 ? "" : line.substring(colon + 1);
            if (value.startsWith(" ")) value = value.substring(1);
            if (hasData) data.append('\n');
            data.append(value); hasData = true;
            if (data.length() > MAX_EVENT) throw new IOException("SSE event too large");
        }
    }
    private String readLine() throws IOException {
        if (eof) return null;
        StringBuilder line = new StringBuilder();
        for (;;) {
            int c = reader.read();
            if (c == -1) { eof = true; return line.length() == 0 ? null : line.toString(); }
            if (c == '\n') {
                if (line.length() > 0 && line.charAt(line.length() - 1) == '\r') line.setLength(line.length() - 1);
                return line.toString();
            }
            if (line.length() >= MAX_LINE) throw new IOException("SSE line too large");
            line.append((char) c);
        }
    }
}
