package ru.geministation;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import okhttp3.Dns;
import org.junit.Test;
import static org.junit.Assert.*;

public class TimedDnsTest {
    @Test public void cacheExpiresAndCanBeInvalidated() throws Exception {
        AtomicInteger lookups = new AtomicInteger(), hits = new AtomicInteger();
        AtomicLong clock = new AtomicLong();
        Dns upstream = name -> { lookups.incrementAndGet(); return Collections.singletonList(InetAddress.getByAddress(new byte[]{1, 2, 3, 4})); };
        TimedDns cache = new TimedDns(upstream, clock::get, hits::incrementAndGet);
        cache.lookup("example.test"); cache.lookup("example.test");
        assertEquals(1, lookups.get()); assertEquals(1, hits.get());
        clock.set(TimeUnit.SECONDS.toNanos(60)); cache.lookup("example.test");
        assertEquals(2, lookups.get());
        cache.clear(); cache.lookup("example.test"); assertEquals(3, lookups.get());
    }
    @Test public void failedLookupIsNotCachedAndDoesNotFallback() throws Exception {
        AtomicInteger lookups = new AtomicInteger();
        TimedDns cache = new TimedDns(name -> { lookups.incrementAndGet(); throw new UnknownHostException(); }, () -> 0, () -> {});
        for (int i = 0; i < 2; i++) {
            try { cache.lookup("example.test"); fail("expected DNS failure"); } catch (UnknownHostException expected) { }
        }
        assertEquals(2, lookups.get());
    }
}
