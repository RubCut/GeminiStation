package ru.geministation;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.media.AudioManager;
import android.provider.Settings;

/** Actions are executed on the UI thread; navigation waits until the spoken acknowledgement finishes. */
final class DeviceActions {
    interface Navigation { boolean open(); }
    static final class Outcome {
        final String text;
        final Navigation navigation;
        Outcome(String text, Navigation navigation) { this.text = text; this.navigation = navigation; }
    }
    private final Activity activity;
    DeviceActions(Activity activity) { this.activity = activity; }

    Outcome prepare(DeviceToolSpec.Action action, VisibleApps apps) {
        if (action == null) return new Outcome("Не удалось выполнить эту команду.", null);
        try {
            switch (action.kind) {
                case OPEN_APP: {
                    VisibleApps.App app = apps.get(action.number);
                    if (!VisibleApps.stillVisible(activity, app)) return new Outcome("Приложение сейчас недоступно.", null);
                    return new Outcome("Открываю " + app.label + ".", () -> {
                        if (!VisibleApps.stillVisible(activity, app)) return false;
                        Intent intent = new Intent(Intent.ACTION_MAIN).addCategory(app.category).setComponent(app.component);
                        return open(intent);
                    });
                }
                case OPEN_LINK: {
                    VisibleApps.App app = apps.get(action.number);
                    if (VisibleApps.linkIntent(activity, app, action.data) == null)
                        return new Outcome("Это приложение не поддерживает такую ссылку.", null);
                    return new Outcome("Открываю ссылку в " + app.label + ".", () -> {
                        Intent intent = VisibleApps.linkIntent(activity, app, action.data);
                        return intent != null && open(intent);
                    });
                }
                case OPEN_SETTINGS: {
                    Intent intent = new Intent(Settings.ACTION_SETTINGS);
                    if (!canOpen(intent)) return new Outcome("Не удалось найти доступные настройки устройства.", null);
                    return new Outcome("Открываю настройки.", () -> canOpen(intent) && open(intent));
                }
                case GO_HOME: {
                    Intent intent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
                    if (!canOpen(intent)) return new Outcome("Не удалось найти рабочий стол.", null);
                    return new Outcome("Возвращаюсь на рабочий стол.", () -> canOpen(intent) && open(intent));
                }
                case GET_VOLUME:
                case SET_VOLUME:
                case ADJUST_VOLUME:
                    return volume(action);
                default: return new Outcome("Не удалось выполнить эту команду.", null);
            }
        } catch (RuntimeException e) {
            AppLog.add("Действие: ошибка подготовки " + e.getClass().getSimpleName());
            return new Outcome("Не удалось выполнить команду на устройстве.", null);
        }
    }
    private Outcome volume(DeviceToolSpec.Action action) {
        AudioManager audio = (AudioManager) activity.getSystemService(Activity.AUDIO_SERVICE);
        if (audio == null) return new Outcome("Управление громкостью недоступно.", null);
        int max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        if (max <= 0) return new Outcome("Управление громкостью недоступно.", null);
        int before = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
        if (action.kind != DeviceToolSpec.Kind.GET_VOLUME) {
            if (audio.isVolumeFixed()) return new Outcome("Громкость на этом устройстве изменить не удалось.", null);
            int target = action.kind == DeviceToolSpec.Kind.SET_VOLUME
                    ? Math.round(action.number * max / 100f)
                    : before + action.number * Math.max(1, Math.round(max * .1f));
            target = Math.max(0, Math.min(max, target));
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, target, AudioManager.FLAG_SHOW_UI);
            int actual = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
            if (target != before && actual == before) return new Outcome("Громкость изменить не удалось.", null);
            before = actual;
            AppLog.add("Действие: изменена громкость мультимедиа");
        }
        int percent = Math.round(before * 100f / max);
        return new Outcome("Громкость " + percent + " процентов.", null);
    }
    private boolean canOpen(Intent intent) {
        ResolveInfo resolved = activity.getPackageManager().resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY);
        return resolved != null && VisibleApps.accessible(activity, resolved.activityInfo);
    }
    private boolean open(Intent intent) {
        try {
            activity.startActivity(intent);
            AppLog.add("Действие: переход запущен");
            return true;
        } catch (RuntimeException e) {
            AppLog.add("Действие: не удалось открыть экран " + e.getClass().getSimpleName());
            return false;
        }
    }
}
