package ru.geministation;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/** Optional system-wide button route; activated only by the user in Accessibility settings. */
public final class AssistantButtonService extends AccessibilityService {
    private static volatile boolean connected;
    private final AssistantButtonPolicy policy = new AssistantButtonPolicy();
    static boolean isConnected() { return connected; }
    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        connected = true;
        AppLog.add("Кнопка: служба специальных возможностей подключена");
    }
    @Override protected boolean onKeyEvent(KeyEvent event) {
        if (event.getKeyCode() != AssistantButtonPolicy.VOICE_ASSIST) return false;
        // Finish consuming an intercepted press even if the user disables it mid-press.
        AssistantButtonPolicy.Decision decision = policy.key(event.getKeyCode(), event.getAction(), event.getRepeatCount(), AppSettings.buttonEnabled(this));
        if (decision == AssistantButtonPolicy.Decision.PASS) return false;
        if (decision == AssistantButtonPolicy.Decision.CONSUME) return true;
        try {
            Intent intent = new Intent(this, MainActivity.class).setAction(Intent.ACTION_ASSIST)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(intent);
            AppLog.add("Кнопка: получен KEYCODE_VOICE_ASSIST, вызов Gemini");
            return true;
        } catch (RuntimeException e) {
            policy.reset();
            AppLog.add("Кнопка: не удалось открыть Gemini (" + e.getClass().getSimpleName() + ")");
            return false;
        }
    }
    @Override public void onAccessibilityEvent(AccessibilityEvent event) { /* No window content is read. */ }
    @Override public void onInterrupt() { policy.reset(); }
    @Override public void onDestroy() {
        connected = false; policy.reset();
        AppLog.add("Кнопка: служба специальных возможностей отключена");
        super.onDestroy();
    }
}
