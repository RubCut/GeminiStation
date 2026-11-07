package ru.geministation;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.IBinder;
import android.os.SystemClock;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;
import ru.geministation.helperprotocol.HelperProtocol;
import ru.geministation.pluginipc.IPluginRunner;
import ru.geministation.pluginipc.IPluginResult;
import ru.geministation.pluginipc.IPluginShell;

/** Single-use capability, bound to approved ZIP hash and a time-limited runner invocation. */
final class PluginExecution {
    private static final String RUNNER = "ru.geministation.pluginrunner";
    static final class Tool {
        final PluginRegistry.Record record; final JSONObject schema, declaration; final String handler;
        Tool(PluginRegistry.Record record, JSONObject tool) throws Exception {
            this.record = record; handler = tool.getString("handler"); schema = tool.getJSONObject("parameters");
            declaration = new JSONObject().put("name", tool.getString("name")).put("description", tool.getString("description"));
            if (schema.getJSONObject("properties").length() > 0) declaration.put("parameters", schema);
        }
    }
    static java.util.List<Tool> snapshot(Context context) throws Exception {
        java.util.List<Tool> result = new java.util.ArrayList<>();
        for (PluginRegistry.Record record : PluginRegistry.list(context)) if (record.enabled)
            for (int i=0; i<record.archive.manifest.tools.length(); i++) result.add(new Tool(record, record.archive.manifest.tools.getJSONObject(i)));
        return result;
    }
    static JSONArray declarations(List<Tool> tools) { JSONArray out = new JSONArray(); for (Tool tool : tools) out.put(tool.declaration); return out; }
    static Tool find(List<Tool> tools, JSONObject call) {
        if (call == null || !(call.opt("name") instanceof String)) return null;
        String name = call.optString("name");
        for (Tool tool : tools) if (tool.declaration.optString("name").equals(name)) return tool;
        return null;
    }
    static synchronized JSONObject execute(Context context, Tool tool, JSONObject call) throws Exception {
        Context c = context.getApplicationContext();
        String id = tool.record.archive.manifest.id, digest = tool.record.archive.digest;
        PluginRegistry.log(c, id, digest, "Запуск инструмента " + tool.handler);
        try {
            JSONObject result = executeInternal(c, tool, call);
            PluginRegistry.log(c, id, digest, result.has("error") ? "Завершено с ошибкой: " + result.optString("error") : "Выполнение завершено");
            return result;
        } catch (Exception e) {
            PluginRegistry.log(c, id, digest, "Ошибка запуска плагина");
            throw e;
        }
    }
    private static JSONObject executeInternal(Context c, Tool tool, JSONObject call) throws Exception {
        JSONObject args = call.optJSONObject("args");
        if (args == null && !call.has("args")) args = new JSONObject();
        if (!PluginManifest.validArgs(tool.schema, args)) return new JSONObject().put("error", "Некорректные аргументы инструмента");
        final String id = tool.record.archive.manifest.id, digest = tool.record.archive.digest;
        if (!PluginRegistry.permitted(c, id, digest, false)) return new JSONObject().put("error", "Плагин отключён или изменён");
        PackageManager pm = c.getPackageManager();
        final int runnerUid;
        try {
            if (pm.checkSignatures(c.getPackageName(), RUNNER) != PackageManager.SIGNATURE_MATCH) throw new SecurityException("Подпись runner не совпадает");
            runnerUid = pm.getApplicationInfo(RUNNER, 0).uid;
        } catch (Exception e) { return new JSONObject().put("error", "Установите подписанный PluginRunner APK"); }
        final AtomicBoolean active = new AtomicBoolean(true);
        final long deadline = SystemClock.elapsedRealtime() + 45000;
        final AtomicInteger calls = new AtomicInteger();
        IPluginShell shell = tool.record.archive.manifest.shell ? new IPluginShell.Stub() {
            @Override public String run(String command, int timeout) {
                try {
                    if (Binder.getCallingUid() != runnerUid || !active.get() || SystemClock.elapsedRealtime() >= deadline
                            || calls.incrementAndGet() > 12 || timeout < 1 || timeout > 30
                            || command == null || command.getBytes(StandardCharsets.UTF_8).length > HelperProtocol.MAX_COMMAND
                            || !PluginRegistry.permitted(c, id, digest, true)) throw new SecurityException("capability revoked");
                    HelperProtocol.Result result = AdbHelper.run(c, command, timeout);
                    return new JSONObject().put("exit_code", result.exitCode).put("timed_out", result.timedOut)
                            .put("truncated", result.truncated).put("output", new String(result.output, StandardCharsets.UTF_8)).toString();
                } catch (Exception e) {
                    try { return new JSONObject().put("error", "ADB helper недоступен или команда отклонена").toString(); }
                    catch (Exception ignored) { return "{}"; }
                }
            }
        } : null;
        CountDownLatch connected = new CountDownLatch(1), finished = new CountDownLatch(1);
        AtomicReference<IPluginRunner> runner = new AtomicReference<>(); AtomicReference<JSONObject> output = new AtomicReference<>();
        ServiceConnection connection = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder service) { runner.set(IPluginRunner.Stub.asInterface(service)); connected.countDown(); }
            @Override public void onServiceDisconnected(ComponentName name) { connected.countDown(); finished.countDown(); }
            @Override public void onBindingDied(ComponentName name) { connected.countDown(); finished.countDown(); }
            @Override public void onNullBinding(ComponentName name) { connected.countDown(); finished.countDown(); }
        };
        Intent intent = new Intent().setClassName(RUNNER, RUNNER + ".PluginService");
        boolean bound = false;
        try {
            bound = c.bindService(intent, connection, Context.BIND_AUTO_CREATE);
            if (!bound || !connected.await(8, TimeUnit.SECONDS) || runner.get() == null) return new JSONObject().put("error", "Python runner недоступен");
            if (!PluginRegistry.permitted(c, id, digest, false)) return new JSONObject().put("error", "Плагин отключён или изменён");
            try {
            runner.get().executeWithConfig(tool.record.archive.source, tool.handler, args.toString(),
                    PluginRegistry.config(c, id, digest).toString(), shell, new IPluginResult.Stub() {
                @Override public void completed(String result, boolean ok) {
                    if (Binder.getCallingUid() != runnerUid || !active.get()) return;
                    try {
                        if (!ok || result == null || result.getBytes(StandardCharsets.UTF_8).length > 50000)
                            output.compareAndSet(null, new JSONObject().put("error", "Ошибка выполнения плагина или несовместимая версия PluginRunner"));
                        else {
                            JSONObject response = new JSONObject(result);
                            String pluginLog = response.optString("log", "");
                            if (!pluginLog.isEmpty()) PluginRegistry.log(c, id, digest, "Вывод плагина:\n" + pluginLog);
                            if (!response.optBoolean("ok", false) || !response.has("result")) {
                                String type = response.optString("error_type", "");
                                if (type.matches("[A-Za-z_][A-Za-z0-9_]{0,79}")) PluginRegistry.log(c, id, digest, "Тип ошибки: " + type);
                                output.compareAndSet(null, new JSONObject().put("error", "Ошибка выполнения плагина"));
                            } else output.compareAndSet(null, new JSONObject().put("result", response.get("result")));
                        }
                    } catch (Exception e) { try { output.compareAndSet(null, new JSONObject().put("error", "Некорректный результат плагина")); } catch (Exception ignored) { } }
                    finished.countDown();
                }
            });
            } catch (android.os.RemoteException | RuntimeException e) {
                return new JSONObject().put("error", "Не удалось запустить плагин; обновите PluginRunner вместе с приложением");
            }
            long remaining = Math.max(0, deadline - SystemClock.elapsedRealtime());
            if (!finished.await(remaining, TimeUnit.MILLISECONDS)) return new JSONObject().put("error", "Время выполнения плагина истекло");
            return output.get() != null ? output.get() : new JSONObject().put("error", "Python runner завершился без результата");
        } finally {
            active.set(false);
            if (runner.get() != null) try { runner.get().cancel(); } catch (Exception ignored) { }
            if (bound) try { c.unbindService(connection); } catch (Exception ignored) { }
        }
    }
    private PluginExecution() { }
}
