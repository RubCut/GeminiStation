package ru.geministation;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Only restart the foreground service on boot after the user explicitly opted in. */
public final class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction()) || !AppSettings.wakeEnabled(context)) return;
        try { context.startForegroundService(new Intent(context, WebServerService.class)); }
        catch (RuntimeException e) { AppLog.add("Голосовая активация: не удалось запустить службу после загрузки"); }
    }
}
