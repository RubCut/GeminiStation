package ru.geministation.helperprotocol;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Versioned, bounded and mutually authenticated protocol; no command or result is logged. */
public final class HelperProtocol {
    public static final int PORT = 16529, MAGIC = 0x47534831;
    public static final int MAX_COMMAND = 8192, MAX_OUTPUT = 65536, MAX_TIMEOUT = 120, KEY_SIZE = 32;
    public static final String KEY_FILE = "adb-helper.key";
    public static final class Request {
        public final String command;
        public final int timeoutSeconds;
        public Request(String command, int timeoutSeconds) { this.command = command; this.timeoutSeconds = timeoutSeconds; }
    }
    public static final class Result {
        public final int exitCode;
        public final boolean timedOut, truncated;
        public final byte[] output;
        public Result(int exitCode, boolean timedOut, boolean truncated, byte[] output) {
            this.exitCode = exitCode; this.timedOut = timedOut; this.truncated = truncated; this.output = output;
        }
    }
    private static byte[] mac(byte[] key, byte[] nonce, String domain, byte[] body) throws IOException {
        if (key == null || key.length != KEY_SIZE || nonce == null || nonce.length != KEY_SIZE) throw new IOException("Invalid key");
        try {
            Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(key, "HmacSHA256"));
            mac.update(domain.getBytes(StandardCharsets.US_ASCII)); mac.update(nonce); mac.update(body); return mac.doFinal();
        } catch (GeneralSecurityException e) { throw new IOException("Authentication unavailable"); }
    }
    private static void verify(DataInputStream in, byte[] key, byte[] nonce, String domain, byte[] body) throws IOException {
        byte[] received = new byte[KEY_SIZE]; in.readFully(received);
        if (!MessageDigest.isEqual(received, mac(key, nonce, domain, body))) throw new IOException("Authentication failed");
    }
    public static void writeHello(DataOutputStream out, byte[] key, byte[] nonce) throws IOException {
        out.writeInt(MAGIC); out.write(nonce); out.write(mac(key, nonce, "hello", new byte[0])); out.flush();
    }
    public static byte[] readHello(DataInputStream in, byte[] key) throws IOException {
        if (in.readInt() != MAGIC) throw new IOException("Invalid protocol");
        byte[] nonce = new byte[KEY_SIZE]; in.readFully(nonce); verify(in, key, nonce, "hello", new byte[0]); return nonce;
    }
    private static byte[] requestBytes(String command, int timeoutSeconds) throws IOException {
        if (command == null || command.indexOf('\0') >= 0 || timeoutSeconds < 1 || timeoutSeconds > MAX_TIMEOUT)
            throw new IOException("Invalid request");
        byte[] bytes = command.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 1 || bytes.length > MAX_COMMAND) throw new IOException("Invalid request");
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(); DataOutputStream out = new DataOutputStream(buffer);
        out.writeInt(MAGIC); out.writeInt(timeoutSeconds); out.writeInt(bytes.length); out.write(bytes); return buffer.toByteArray();
    }
    public static void writeRequest(DataOutputStream out, String command, int timeoutSeconds, byte[] key, byte[] nonce) throws IOException {
        byte[] body = requestBytes(command, timeoutSeconds); out.write(body); out.write(mac(key, nonce, "request", body)); out.flush();
    }
    public static Request readRequest(DataInputStream in, byte[] key, byte[] nonce) throws IOException {
        if (in.readInt() != MAGIC) throw new IOException("Invalid protocol");
        int timeout = in.readInt(), length = in.readInt();
        if (timeout < 1 || timeout > MAX_TIMEOUT || length < 1 || length > MAX_COMMAND) throw new IOException("Invalid request");
        byte[] bytes = new byte[length]; in.readFully(bytes);
        String command = new String(bytes, StandardCharsets.UTF_8);
        if (command.indexOf('\0') >= 0 || !Arrays.equals(bytes, command.getBytes(StandardCharsets.UTF_8))) throw new IOException("Invalid request");
        verify(in, key, nonce, "request", requestBytes(command, timeout)); return new Request(command, timeout);
    }
    private static byte[] resultBytes(Result result) throws IOException {
        if (result.output == null || result.output.length > MAX_OUTPUT) throw new IOException("Invalid result");
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(); DataOutputStream out = new DataOutputStream(buffer);
        out.writeInt(MAGIC); out.writeInt(result.exitCode); out.writeBoolean(result.timedOut); out.writeBoolean(result.truncated);
        out.writeInt(result.output.length); out.write(result.output); return buffer.toByteArray();
    }
    public static void writeResult(DataOutputStream out, Result result, byte[] key, byte[] nonce) throws IOException {
        byte[] body = resultBytes(result); out.write(body); out.write(mac(key, nonce, "result", body)); out.flush();
    }
    public static Result readResult(DataInputStream in, byte[] key, byte[] nonce) throws IOException {
        if (in.readInt() != MAGIC) throw new IOException("Invalid protocol");
        int code = in.readInt(); boolean timedOut = in.readBoolean(), truncated = in.readBoolean(); int length = in.readInt();
        if (length < 0 || length > MAX_OUTPUT) throw new IOException("Invalid result");
        byte[] output = new byte[length]; in.readFully(output); Result result = new Result(code, timedOut, truncated, output);
        verify(in, key, nonce, "result", resultBytes(result)); return result;
    }
    private HelperProtocol() { }
}
