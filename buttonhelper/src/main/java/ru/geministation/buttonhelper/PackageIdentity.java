package ru.geministation.buttonhelper;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.security.MessageDigest;
import java.util.concurrent.TimeUnit;

/** Verify the two installed packages share a signer, and pin the running helper APK. */
final class PackageIdentity {
    static final String APP = "ru.geministation", HELPER = "ru.geministation.buttonhelper";
    static byte[] snapshot() throws Exception {
        // app_process has no application Context. Both methods are in Android 9's
        // light-greylist. If a vendor blocks them, fail closed rather than trusting a package name.
        Class<?> thread = Class.forName("android.app.ActivityThread");
        Object pm = thread.getMethod("getPackageManager").invoke(null);
        Class<?> iface = Class.forName("android.content.pm.IPackageManager");
        Method check = iface.getMethod("checkSignatures", String.class, String.class);
        if (((Integer) check.invoke(pm, APP, HELPER)) != 0) throw new SecurityException();
        java.lang.Process command = new ProcessBuilder("/system/bin/cmd", "package", "path", "--user", "0", HELPER)
                .redirectError(new File("/dev/null")).start();
        try {
            if (!command.waitFor(3, TimeUnit.SECONDS)) { command.destroyForcibly(); throw new SecurityException(); }
            if (command.exitValue() != 0) throw new SecurityException();
            ByteArrayOutputStream data = new ByteArrayOutputStream();
            try (InputStream in = command.getInputStream()) {
                byte[] buffer = new byte[256]; int n;
                while ((n = in.read(buffer)) != -1) {
                    if (data.size() + n > 4096) throw new SecurityException();
                    data.write(buffer, 0, n);
                }
            }
            String line = data.toString("UTF-8").trim();
            if (!line.startsWith("package:") || line.indexOf('\n') >= 0 || line.indexOf('\r') >= 0) throw new SecurityException();
            File apk = new File(line.substring(8)).getCanonicalFile();
            if (!apk.getPath().startsWith("/data/app/") || !apk.getName().equals("base.apk")
                    || !apk.isFile() || apk.length() > 50L * 1024 * 1024) throw new SecurityException();
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (FileInputStream in = new FileInputStream(apk)) {
                byte[] buffer = new byte[8192]; int n;
                while ((n = in.read(buffer)) != -1) digest.update(buffer, 0, n);
            }
            return digest.digest();
        } finally { if (command.isAlive()) command.destroyForcibly(); }
    }
    private PackageIdentity() { }
}
