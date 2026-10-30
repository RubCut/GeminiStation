package ru.geministation;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import org.xbill.DNS.ARecord;
import org.xbill.DNS.DClass;
import org.xbill.DNS.Flags;
import org.xbill.DNS.Message;
import org.xbill.DNS.Section;
import static org.junit.Assert.*;

public class PlainDnsTest {
    @Test public void resolvesAgainstConfiguredDnsWithoutSystemFallback() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (DatagramSocket socket = new DatagramSocket(0, InetAddress.getByName("127.0.0.1"))) {
            socket.setSoTimeout(5000);
            Future<?> server = executor.submit(() -> {
                try {
                    DatagramPacket packet = new DatagramPacket(new byte[4096], 4096);
                    socket.receive(packet);
                    Message query = new Message(java.util.Arrays.copyOf(packet.getData(), packet.getLength()));
                    Message response = new Message(query.getHeader().getID());
                    response.getHeader().setFlag(Flags.QR);
                    response.getHeader().setFlag(Flags.RA);
                    response.addRecord(query.getQuestion(), Section.QUESTION);
                    response.addRecord(new ARecord(query.getQuestion().getName(), DClass.IN, 60, InetAddress.getByName("192.0.2.25")), Section.ANSWER);
                    byte[] bytes = response.toWire();
                    socket.send(new DatagramPacket(bytes, bytes.length, packet.getSocketAddress()));
                } catch (Exception e) { throw new RuntimeException(e); }
            });
            assertEquals("192.0.2.25", new PlainDns("127.0.0.1", socket.getLocalPort()).lookup("unique.invalid").get(0).getHostAddress());
            server.get(6, TimeUnit.SECONDS);
        } finally { executor.shutdownNow(); }
    }
}
