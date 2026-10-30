package ru.geministation;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import okhttp3.Dns;
import org.xbill.DNS.ARecord;
import org.xbill.DNS.Lookup;
import org.xbill.DNS.Name;
import org.xbill.DNS.SimpleResolver;
import org.xbill.DNS.Type;

/** DNS over UDP/TCP to a user-selected numeric address. No fallback to the system resolver. */
final class PlainDns implements Dns {
    private final SimpleResolver resolver;
    PlainDns(String address) throws UnknownHostException { this(address, 53); }
    PlainDns(String address, int port) throws UnknownHostException {
        if (!DnsSettings.isIp(address)) throw new IllegalArgumentException("DNS address must be an IP literal");
        resolver = new SimpleResolver(InetAddress.getByName(address));
        resolver.setPort(port);
        resolver.setTimeout(Duration.ofSeconds(8));
    }
    @Override public List<InetAddress> lookup(String hostname) throws UnknownHostException {
        try {
            Lookup lookup = new Lookup(Name.fromString(hostname, Name.root), Type.A);
            lookup.setResolver(resolver);
            lookup.setCache(null);
            lookup.setHostsFileParser(null);
            org.xbill.DNS.Record[] records = lookup.run();
            List<InetAddress> result = new ArrayList<>();
            if (records != null) for (org.xbill.DNS.Record record : records)
                if (record instanceof ARecord) result.add(((ARecord) record).getAddress());
            if (result.isEmpty()) throw new UnknownHostException("DNS lookup failed");
            return result;
        } catch (UnknownHostException e) { throw e; }
        catch (Exception e) { throw new UnknownHostException("DNS lookup failed"); }
    }
}
