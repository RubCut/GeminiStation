package ru.geministation;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import okhttp3.Dns;

/** Short-lived cache of successful custom DNS lookups; no system-DNS fallback. */
final class TimedDns implements Dns {
    private static final long TTL_NANOS = TimeUnit.SECONDS.toNanos(60);
    private final Dns upstream;
    private final LongSupplier clock;
    private final Runnable cacheHit;
    private final Map<String, Entry> entries = new HashMap<>();
    private static final class Entry {
        final List<InetAddress> addresses;
        final long expires;
        Entry(List<InetAddress> addresses, long expires) { this.addresses = addresses; this.expires = expires; }
    }
    TimedDns(Dns upstream, LongSupplier clock, Runnable cacheHit) { this.upstream = upstream; this.clock = clock; this.cacheHit = cacheHit; }
    @Override public synchronized List<InetAddress> lookup(String hostname) throws UnknownHostException {
        long now = clock.getAsLong();
        Entry cached = entries.get(hostname);
        if (cached != null && now < cached.expires) {
            cacheHit.run();
            return cached.addresses;
        }
        List<InetAddress> addresses = new ArrayList<>(upstream.lookup(hostname));
        if (addresses.isEmpty()) throw new UnknownHostException("DNS returned no addresses");
        if (entries.size() >= 16) entries.clear();
        List<InetAddress> result = Collections.unmodifiableList(addresses);
        entries.put(hostname, new Entry(result, clock.getAsLong() + TTL_NANOS));
        return result;
    }
    synchronized void clear() { entries.clear(); }
}
