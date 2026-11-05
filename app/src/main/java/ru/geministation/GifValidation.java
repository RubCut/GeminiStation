package ru.geministation;

/** Bounded structural GIF validation before forwarding untrusted animations to the station. */
final class GifValidation {
    static final int MAX_BYTES = 512 * 1024, MAX_FRAMES = 600;
    private final byte[] data; private int pos;
    private GifValidation(byte[] data) { this.data = data; }
    private int read() { if (pos >= data.length) throw new IllegalArgumentException("truncated"); return data[pos++] & 255; }
    private int word() { int a = read(); return a | read() << 8; }
    private void skip(int n) { if (n < 0 || n > data.length - pos) throw new IllegalArgumentException("truncated"); pos += n; }
    private int blocks() { int total = 0, n; while ((n = read()) != 0) { skip(n); total += n; } return total; }
    private void imageData(int minCodeSize, int pixels) {
        java.io.ByteArrayOutputStream compressed = new java.io.ByteArrayOutputStream();
        int size;
        while ((size = read()) != 0) {
            if (size > data.length - pos || compressed.size() + size > 16384) throw new IllegalArgumentException("image data too large");
            compressed.write(data, pos, size); pos += size;
        }
        byte[] bytes = compressed.toByteArray();
        int clear = 1 << minCodeSize, end = clear + 1, next = end + 1, bits = minCodeSize + 1, bit = 0, previous = -1, output = 0;
        int[] lengths = new int[4096]; java.util.Arrays.fill(lengths, 0, clear, 1);
        boolean ended = false;
        while (bit + bits <= bytes.length * 8) {
            int code = 0;
            for (int j = 0; j < bits; j++) code |= ((bytes[(bit + j) / 8] >> ((bit + j) % 8)) & 1) << j;
            bit += bits;
            if (code == clear) { next = end + 1; bits = minCodeSize + 1; previous = -1; continue; }
            if (code == end) { ended = true; break; }
            int count;
            if (code < next && code < lengths.length && lengths[code] > 0) count = lengths[code];
            else if (code == next && previous >= 0 && next < lengths.length) count = lengths[previous] + 1;
            else throw new IllegalArgumentException("invalid LZW code");
            output += count;
            if (output > pixels) throw new IllegalArgumentException("expanded frame too large");
            if (previous >= 0 && next < lengths.length) {
                lengths[next++] = lengths[previous] + 1;
                if (next == (1 << bits) && bits < 12) bits++;
            }
            previous = code;
        }
        if (!ended || output != pixels) throw new IllegalArgumentException("invalid LZW frame");
    }
    private void parse() {
        if (data.length < 14 || data.length > MAX_BYTES) throw new IllegalArgumentException("size");
        String sig = new String(data, 0, 6, java.nio.charset.StandardCharsets.US_ASCII); pos = 6;
        if (!"GIF87a".equals(sig) && !"GIF89a".equals(sig)) throw new IllegalArgumentException("signature");
        if (word() != 25 || word() != 16) throw new IllegalArgumentException("dimensions");
        int flags = read(); read(); read();
        boolean global = (flags & 128) != 0;
        if (global) skip(3 * (1 << ((flags & 7) + 1)));
        int frames = 0, delay = 0, quick = 0;
        boolean trailer = false;
        while (pos < data.length) {
            int marker = read();
            if (marker == 0x3b) { trailer = true; break; }
            if (marker == 0x21) {
                int label = read();
                if (label == 0xf9) {
                    if (read() != 4) throw new IllegalArgumentException("control");
                    int packed = read(); if (((packed >> 2) & 7) > 3) throw new IllegalArgumentException("disposal");
                    delay = word(); read(); if (read() != 0) throw new IllegalArgumentException("control");
                } else if (label == 0xff || label == 0xfe) blocks();
                else throw new IllegalArgumentException("unsupported extension");
            } else if (marker == 0x2c) {
                int x = word(), y = word(), width = word(), height = word(), packed = read();
                if (width < 1 || height < 1 || x + width > 25 || y + height > 16) throw new IllegalArgumentException("frame dimensions");
                if ((packed & 128) != 0) skip(3 * (1 << ((packed & 7) + 1))); else if (!global) throw new IllegalArgumentException("palette");
                int lzw = read(); if (lzw < 2 || lzw > 8) throw new IllegalArgumentException("image data");
                imageData(lzw, width * height);
                if (++frames > MAX_FRAMES) throw new IllegalArgumentException("too many frames");
                if (delay < 2) quick++; if (delay > 1000) throw new IllegalArgumentException("frame duration");
                delay = 0;
            } else throw new IllegalArgumentException("unexpected block");
        }
        if (!trailer || pos != data.length || frames == 0 || (frames > 1 && quick > 0)) throw new IllegalArgumentException("incomplete or fast animation");
    }
    static boolean valid(byte[] data) {
        if (data == null) return false;
        try { new GifValidation(data).parse(); return true; } catch (RuntimeException e) { return false; }
    }
    private GifValidation() { data = null; }
}
