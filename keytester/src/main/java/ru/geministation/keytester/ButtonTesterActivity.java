package ru.geministation.keytester;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.graphics.Color;
import android.graphics.Typeface;
import android.util.Log;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Diagnostic only: receives keys delivered to the focused Activity, not globally. */
public final class ButtonTesterActivity extends Activity {
    private static final String TAG = "ButtonTester";
    private static final String PREFS = "events";
    private ButtonHistory history;
    private TextView latest, entries;
    private ScrollView scroll;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        history = new ButtonHistory(getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("history", ""));
        buildUi();
        render();
        Log.i(TAG, "Тестер открыт; записываются только события, полученные активным окном");
    }
    private int dp(int value) { return (int) (value * getResources().getDisplayMetrics().density + .5f); }
    private TextView label(String text, int size, int color) {
        TextView result = new TextView(this);
        result.setText(text); result.setTextColor(color); result.setTextSize(size);
        return result;
    }
    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(16, 23, 37));
        root.setPadding(dp(32), dp(22), dp(32), dp(22));
        TextView title = label("Тестер кнопок", 26, Color.WHITE);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        root.addView(title);
        TextView help = label("Нажимайте кнопки, пока открыт этот экран. HOME, питание и другие системные кнопки прошивка может не передать приложению. История хранится на устройстве (100 событий).", 15, 0xffbbcee8);
        help.setPadding(0, dp(6), 0, dp(8));
        root.addView(help);
        latest = label("Ожидаю нажатие…", 20, 0xffa8c7fa);
        latest.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        root.addView(latest);
        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        Button clear = new Button(this); clear.setText("Очистить историю");
        clear.setOnClickListener(v -> {
            history.clear(); save(); render();
            Log.i(TAG, "История очищена");
        });
        Button close = new Button(this); close.setText("Закрыть");
        close.setOnClickListener(v -> finish());
        buttons.addView(clear); buttons.addView(close);
        root.addView(buttons);
        TextView section = label("Журнал: нажатие DOWN / отпускание UP", 16, Color.WHITE);
        root.addView(section);
        scroll = new ScrollView(this);
        entries = label("", 14, 0xffd6e4fb);
        entries.setTypeface(Typeface.MONOSPACE);
        entries.setTextIsSelectable(false);
        scroll.addView(entries);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
    }
    private void save() {
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("history", history.serialize()).apply();
    }
    private void render() {
        String current = history.latest();
        latest.setText(current == null ? "Ожидаю нажатие…" : current);
        entries.setText(history.size() == 0 ? "Пока нет событий." : history.serialize());
        scroll.post(() -> scroll.smoothScrollTo(0, entries.getHeight()));
    }
    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        // Repeats only add noise (and can flood logs if a button is held).
        if (event.getAction() != KeyEvent.ACTION_DOWN || event.getRepeatCount() == 0) {
            String action;
            switch (event.getAction()) {
                case KeyEvent.ACTION_DOWN: action = "DOWN"; break;
                case KeyEvent.ACTION_UP: action = "UP"; break;
                case KeyEvent.ACTION_MULTIPLE: action = "MULTIPLE"; break;
                default: action = "ACTION_" + event.getAction();
            }
            InputDevice device = event.getDevice();
            String name = ButtonHistory.oneLine(device == null ? "неизвестно" : device.getName(), 80);
            String time = new SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT).format(new Date());
            String line = String.format(Locale.ROOT,
                    "%s %s keyCode=%d (%s) scanCode=%d deviceId=%d [%s] source=0x%08x flags=0x%08x",
                    time, action, event.getKeyCode(), KeyEvent.keyCodeToString(event.getKeyCode()),
                    event.getScanCode(), event.getDeviceId(), name, event.getSource(), event.getFlags());
            history.add(line); save(); render();
            Log.i(TAG, line);
        }
        return super.dispatchKeyEvent(event);
    }
}
