package ru.geministation;

import android.content.Context;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import ru.geministation.helperprotocol.HelperProtocol;

/** Internal helper transport; commands are available only to internal code or the consent-gated plugin broker. */
final class AdbHelper {
    private static synchronized byte[] key(Context context) throws IOException {
        if (!context.getFileStreamPath(HelperProtocol.KEY_FILE).exists()) {
            byte[] created = new byte[HelperProtocol.KEY_SIZE]; new SecureRandom().nextBytes(created);
            String temp = HelperProtocol.KEY_FILE + ".tmp";
            try {
                try (FileOutputStream out = context.openFileOutput(temp, Context.MODE_PRIVATE)) { out.write(created); out.getFD().sync(); }
                if (!context.getFileStreamPath(temp).renameTo(context.getFileStreamPath(HelperProtocol.KEY_FILE)))
                    throw new IOException("Unable to create helper key");
            } finally { Arrays.fill(created, (byte) 0); context.deleteFile(temp); }
        }
        try (FileInputStream in = context.openFileInput(HelperProtocol.KEY_FILE)) {
            byte[] data = new byte[HelperProtocol.KEY_SIZE]; int count = 0, n;
            while (count < data.length && (n = in.read(data, count, data.length - count)) > 0) count += n;
            if (count != data.length || in.read() != -1) throw new IOException("Invalid helper key");
            return data;
        }
    }
    static HelperProtocol.Result run(Context context, String command, int timeoutSeconds) throws IOException {
        byte[] key = key(context);
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), HelperProtocol.PORT), 3000);
            socket.setSoTimeout((Math.min(Math.max(timeoutSeconds, 1), HelperProtocol.MAX_TIMEOUT) + 7) * 1000);
            DataInputStream in = new DataInputStream(socket.getInputStream());
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            byte[] nonce = HelperProtocol.readHello(in, key);
            HelperProtocol.writeRequest(out, command, timeoutSeconds, key, nonce);
            return HelperProtocol.readResult(in, key, nonce);
        } finally { Arrays.fill(key, (byte) 0); }
    }
    /** Safe fixed test: never uses request or model data as command input. */
    static boolean probe(Context context) {
        try {
            HelperProtocol.Result result = run(context, "id -u", 3);
            return result.exitCode == 0 && !result.timedOut && !result.truncated
                    && "2000".equals(new String(result.output, StandardCharsets.UTF_8).trim());
        } catch (Exception e) { return false; }
    }
    private AdbHelper() { }
}
