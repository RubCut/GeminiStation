package ru.geministation.buttonhelper;

import org.junit.Test;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;
import ru.geministation.helperprotocol.HelperProtocol;
import static org.junit.Assert.*;

public class HelperProtocolTest {
    private final byte[] key = new byte[32], nonce = new byte[32];
    private DataInputStream input(byte[] value) { return new DataInputStream(new ByteArrayInputStream(value)); }
    @Test public void authenticatedRoundTripSupportsUnicodeAndResult() throws Exception {
        Arrays.fill(key, (byte) 11); Arrays.fill(nonce, (byte) 22);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        HelperProtocol.writeHello(new DataOutputStream(bytes), key, nonce);
        assertArrayEquals(nonce, HelperProtocol.readHello(input(bytes.toByteArray()), key));
        bytes.reset(); HelperProtocol.writeRequest(new DataOutputStream(bytes), "echo 'привет'", 5, key, nonce);
        HelperProtocol.Request request = HelperProtocol.readRequest(input(bytes.toByteArray()), key, nonce);
        assertEquals("echo 'привет'", request.command); assertEquals(5, request.timeoutSeconds);
        bytes.reset(); HelperProtocol.writeResult(new DataOutputStream(bytes), new HelperProtocol.Result(7, false, true, new byte[]{1, 2}), key, nonce);
        HelperProtocol.Result result = HelperProtocol.readResult(input(bytes.toByteArray()), key, nonce);
        assertEquals(7, result.exitCode); assertFalse(result.timedOut); assertTrue(result.truncated); assertArrayEquals(new byte[]{1, 2}, result.output);
    }
    @Test public void wrongKeysTamperingAndReplayAreRejected() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        HelperProtocol.writeHello(new DataOutputStream(bytes), key, nonce);
        byte[] bad = bytes.toByteArray(); bad[bad.length - 1] ^= 1;
        try { HelperProtocol.readHello(input(bad), key); fail(); } catch (IOException expected) { }
        bytes.reset(); HelperProtocol.writeRequest(new DataOutputStream(bytes), "echo safe", 3, key, nonce);
        byte[] request = bytes.toByteArray(); byte[] otherKey = key.clone(); otherKey[0] = 1;
        try { HelperProtocol.readRequest(input(request), otherKey, nonce); fail(); } catch (IOException expected) { }
        byte[] otherNonce = nonce.clone(); otherNonce[0] = 1;
        try { HelperProtocol.readRequest(input(request), key, otherNonce); fail(); } catch (IOException expected) { }
        request[12] ^= 1;
        try { HelperProtocol.readRequest(input(request), key, nonce); fail(); } catch (IOException expected) { }
        bytes.reset(); HelperProtocol.writeResult(new DataOutputStream(bytes), new HelperProtocol.Result(0, false, false, new byte[]{42}), key, nonce);
        bad = bytes.toByteArray(); bad[bad.length - 1] ^= 1;
        try { HelperProtocol.readResult(input(bad), key, nonce); fail(); } catch (IOException expected) { }
    }
    @Test public void rejectsBadLengthsAndTimeoutsBeforeAllocation() throws Exception {
        for (String command : new String[]{"", "x\0y", new String(new char[8193]).replace('\0', 'x')}) {
            try { HelperProtocol.writeRequest(new DataOutputStream(new ByteArrayOutputStream()), command, 1, key, nonce); fail(); }
            catch (IOException expected) { }
        }
        for (int timeout : new int[]{0, 121}) {
            try { HelperProtocol.writeRequest(new DataOutputStream(new ByteArrayOutputStream()), "x", timeout, key, nonce); fail(); }
            catch (IOException expected) { }
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); DataOutputStream out = new DataOutputStream(bytes);
        out.writeInt(HelperProtocol.MAGIC); out.writeInt(1); out.writeInt(Integer.MAX_VALUE);
        try { HelperProtocol.readRequest(input(bytes.toByteArray()), key, nonce); fail(); } catch (IOException expected) { }
    }
}
