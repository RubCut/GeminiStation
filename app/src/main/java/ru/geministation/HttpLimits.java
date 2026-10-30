package ru.geministation;

import java.io.IOException;
import okhttp3.ResponseBody;
import okio.BufferedSource;

/** Bound both fixed-length and chunked responses before buffering or decoding. */
final class HttpLimits {
    static String readUtf8(ResponseBody body, int limit) throws IOException {
        if (body == null) throw new IOException("Empty response");
        if (limit < 0 || body.contentLength() > limit) throw new IOException("Response too large");
        BufferedSource source = body.source();
        source.request((long) limit + 1);
        if (source.buffer().size() > limit) throw new IOException("Response too large");
        return source.readUtf8();
    }
    private HttpLimits() { }
}
