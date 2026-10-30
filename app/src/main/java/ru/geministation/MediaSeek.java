package ru.geministation;

import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import java.util.List;

/** Access and command only Android's highest-priority active session, not all players. */
final class MediaSeek {
    static ComponentName component(Context c) { return new ComponentName(c, MediaAccessService.class); }
    static boolean accessGranted(Context c) {
        NotificationManager manager = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        try { return manager != null && manager.isNotificationListenerAccessGranted(component(c)); }
        catch (RuntimeException e) { return false; }
    }
    static String seek(Context c, String mode, int seconds) {
        if (!MediaSeekPolicy.valid(mode, seconds)) return "Некорректное время перемотки.";
        MediaSessionManager manager = (MediaSessionManager) c.getSystemService(Context.MEDIA_SESSION_SERVICE);
        if (manager == null) return "Системное управление медиасессиями недоступно.";
        try {
            List<MediaController> sessions = manager.getActiveSessions(component(c));
            if (sessions.isEmpty()) return "Не найдена активная медиасессия Android.";
            MediaController controller = sessions.get(0);
            PlaybackState state = controller.getPlaybackState();
            if (state == null || (state.getActions() & PlaybackState.ACTION_SEEK_TO) == 0)
                return "Текущий медиаплеер не сообщил о поддержке точной перемотки.";
            MediaMetadata metadata = controller.getMetadata();
            long duration = metadata != null && metadata.containsKey(MediaMetadata.METADATA_KEY_DURATION)
                    ? metadata.getLong(MediaMetadata.METADATA_KEY_DURATION) : -1;
            // Android 9 already updates the reported playback position using elapsed time and speed.
            long target = MediaSeekPolicy.target(mode, seconds, state.getPosition(), duration);
            if (target < 0) return "Не удалось определить нужную позицию: проверьте время и состояние плеера.";
            controller.getTransportControls().seekTo(target);
            AppLog.add("Медиа: отправлена точная перемотка; позиция " + target + " мс, выполнение не подтверждено");
            return "Отправлена команда перемотки на " + (target / 1000) + " секунд. Плеер не подтвердил выполнение.";
        } catch (SecurityException e) {
            return "Для точной перемотки включите доступ к уведомлениям для Gemini Station. Инструкция в веб-панели.";
        } catch (RuntimeException e) {
            AppLog.add("Медиа: ошибка перемотки (" + e.getClass().getSimpleName() + ")");
            return "Не удалось отправить перемотку медиаплееру.";
        }
    }
    private MediaSeek() { }
}
