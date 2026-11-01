package ru.geministation;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;

/** Local physical approval only; unexported, content and capabilities bound to ZIP hash. */
public final class PluginApprovalActivity extends Activity {
    private String token;
    private boolean resolved;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        token = getIntent().getStringExtra("token");
        PluginRegistry.Pending p = PluginRegistry.pending(token);
        if (p == null) { finish(); return; }
        PluginManifest manifest = p.record.archive.manifest;
        String warning = "Плагин: " + manifest.name + " (" + manifest.id + ")\nВерсия: " + manifest.version
                + "\nSHA-256: " + p.record.archive.digest
                + "\nИнструментов: " + manifest.tools.length()
                + "\n\nВыполняется произвольный Python-код в отдельном приложении. Он имеет доступ к сети, может взаимодействовать с доступными локальными сервисами, включая веб-панель без пароля, и отправлять полученные данные в сеть. Устанавливайте только доверенный код."
                + (manifest.shell ? "\n\nОПАСНО: плагин запросил ADB shell. После разрешения его инструменты смогут выполнять команды с правами shell без повторного подтверждения. Это не root, но такие команды могут изменять настройки и данные устройства." : "\n\nADB shell не запрошен.");
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Установить / включить Python-плагин?").setMessage(warning)
                .setPositiveButton("Разрешить", (d, which) -> finishDecision(true))
                .setNegativeButton("Отмена", (d, which) -> finishDecision(false))
                .setOnCancelListener(d -> finishDecision(false)).show();
        if (dialog.getWindow() != null) BrandFont.apply(dialog.getWindow().getDecorView());
    }
    private void finishDecision(boolean accepted) {
        if (resolved) return; resolved = true;
        try {
            boolean applied = PluginRegistry.resolve(this, token, accepted);
            AppLog.add("Плагины: " + (applied && accepted ? "установка подтверждена" : "установка отменена"));
        } catch (Exception e) { AppLog.add("Плагины: не удалось применить подтверждение"); }
        finish();
    }
    @Override public void onDestroy() {
        if (!resolved && !isChangingConfigurations()) {
            try { PluginRegistry.resolve(this, token, false); } catch (Exception ignored) { }
        }
        super.onDestroy();
    }
}
