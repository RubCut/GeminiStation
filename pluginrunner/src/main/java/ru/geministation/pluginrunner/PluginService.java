package ru.geministation.pluginrunner;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.os.Process;
import android.os.RemoteException;
import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;
import java.util.concurrent.atomic.AtomicBoolean;
import ru.geministation.pluginipc.IPluginRunner;
import ru.geministation.pluginipc.IPluginResult;
import ru.geministation.pluginipc.IPluginShell;

/** Signed caller only, no main-app private files or credentials in this process. */
public final class PluginService extends Service {
    private final AtomicBoolean busy = new AtomicBoolean();
    private boolean trustedCaller() {
        String[] names = getPackageManager().getPackagesForUid(Binder.getCallingUid());
        if (names == null) return false;
        for (String name : names) if ("ru.geministation".equals(name)
                && getPackageManager().checkSignatures(getPackageName(), name) == android.content.pm.PackageManager.SIGNATURE_MATCH) return true;
        return false;
    }
    private final IPluginRunner.Stub binder = new IPluginRunner.Stub() {
        @Override public void execute(String source, String handler, String argsJson, IPluginShell shell, IPluginResult result) {
            start(source, handler, argsJson, "{}", shell, result, false);
        }
        @Override public void executeWithConfig(String source, String handler, String argsJson, String configJson, IPluginShell shell, IPluginResult result) {
            start(source, handler, argsJson, configJson, shell, result, true);
        }
        private void start(String source, String handler, String argsJson, String configJson, IPluginShell shell, IPluginResult result, boolean withConfig) {
            if (!trustedCaller()) throw new SecurityException("Unauthorized caller");
            if (configJson == null || configJson.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 8192 || source == null || source.length() > 65536 || handler == null || !handler.matches("[A-Za-z_][A-Za-z0-9_]{0,63}")
                    || argsJson == null || argsJson.length() > 16384 || result == null || !busy.compareAndSet(false, true)) {
                try { if (result != null) result.completed("Plugin request rejected", false); } catch (RemoteException ignored) { }
                return;
            }
            Thread thread = new Thread(() -> {
                try {
                    if (!Python.isStarted()) Python.start(new AndroidPlatform(PluginService.this));
                    String output = withConfig
                            ? Python.getInstance().getModule("plugin_host").callAttr("invoke_with_config", source, handler, argsJson, configJson, shell).toString()
                            : Python.getInstance().getModule("plugin_host").callAttr("invoke", source, handler, argsJson, shell).toString();
                    result.completed(output, true);
                } catch (Throwable e) {
                    // Do not leak Python exception messages, arguments, keys or stdout to logs.
                    try { result.completed("Plugin execution failed", false); } catch (Exception ignored) { }
                } finally { Process.killProcess(Process.myPid()); }
            }, "python-plugin");
            thread.start();
        }
        @Override public void cancel() {
            if (!trustedCaller()) throw new SecurityException("Unauthorized caller");
            Process.killProcess(Process.myPid());
        }
    };
    @Override public IBinder onBind(Intent intent) { return binder; }
}
