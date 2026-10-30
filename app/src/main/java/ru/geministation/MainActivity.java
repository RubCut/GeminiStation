package ru.geministation;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.provider.Settings;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int MICROPHONE_REQUEST = 100;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final JSONArray history = new JSONArray();
    private GeminiClient client;
    private SecretStore secrets;
    private TextToSpeech tts;
    private SpeechRecognizer recognizer;
    private TextView status, conversation;
    private ScrollView scroll;
    private EditText input;
    private Button send, microphone, clear;
    private boolean busy, listening, destroyed, ttsReady;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        secrets = new SecretStore(this);
        client = new GeminiClient();
        buildUi();
        try {
            startForegroundService(new Intent(this, WebServerService.class));
        } catch (RuntimeException e) { updateStatus("Не удалось запустить веб-сервер."); }
        tts = new TextToSpeech(this, result -> {
            if (destroyed) return;
            if (result == TextToSpeech.SUCCESS) {
                int language = tts.setLanguage(new Locale("ru", "RU"));
                ttsReady = language != TextToSpeech.LANG_MISSING_DATA && language != TextToSpeech.LANG_NOT_SUPPORTED;
            }
            if (!ttsReady) updateStatus("Синтез речи недоступен. Ответы будут показаны текстом.");
        });
        if (Intent.ACTION_ASSIST.equals(getIntent().getAction())) startVoice();
    }
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (Intent.ACTION_ASSIST.equals(intent.getAction())) startVoice();
    }
    private int dp(int value) { return (int) (value * getResources().getDisplayMetrics().density + .5f); }
    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(40), dp(22), dp(40), dp(22));
        root.setBackgroundColor(Color.rgb(16, 23, 37));
        TextView title = new TextView(this);
        title.setText("Gemini Station"); title.setTextSize(26); title.setTextColor(Color.WHITE);
        root.addView(title);
        status = new TextView(this); status.setTextColor(0xffb7c8e3); status.setTextSize(16);
        updateStatus("Скажите запрос или введите его текстом. Для начала добавьте API-ключ в настройках.");
        root.addView(status);
        scroll = new ScrollView(this);
        conversation = new TextView(this); conversation.setTextColor(Color.WHITE); conversation.setTextSize(19);
        conversation.setTextIsSelectable(false); conversation.setPadding(0, dp(12), 0, dp(12));
        scroll.addView(conversation);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        input = new EditText(this); input.setSingleLine(true); input.setTextColor(Color.WHITE);
        input.setHintTextColor(0xff9baac0); input.setHint("Ваш вопрос");
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setOnEditorActionListener((v, action, event) -> { submit(); return true; });
        root.addView(input);
        LinearLayout actions = new LinearLayout(this);
        microphone = button("Говорить", v -> startVoice());
        send = button("Отправить", v -> submit());
        clear = button("Новый диалог", v -> clearConversation());
        Button settings = button("Настройки", v -> showSettings());
        Button web = button("Веб", v -> showWebInfo());
        actions.addView(microphone); actions.addView(send); actions.addView(clear); actions.addView(settings); actions.addView(web);
        root.addView(actions);
        setContentView(root);
        microphone.requestFocus();
    }
    private Button button(String label, View.OnClickListener listener) {
        Button button = new Button(this); button.setText(label); button.setAllCaps(false);
        button.setOnClickListener(listener); return button;
    }
    private void updateStatus(String message) {
        status.setText(message);
        AppLog.add(message);
    }
    private void showWebInfo() {
        String state = WebServerService.isRunning() ? "Работает" : "Не запущен (перезапустите приложение)";
        new AlertDialog.Builder(this).setTitle("Веб-интерфейс")
                .setMessage(state + "\n\nАдрес: " + WebServerService.address() + "\n\nКод доступа: " + WebServerService.token()
                        + "\n\nHTTP не шифруется. Безопаснее открыть через ADB-проброс на localhost.")
                .setPositiveButton("Закрыть", null).show();
    }
    private void showSettings() {
        LinearLayout layout = new LinearLayout(this); layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(24), dp(8), dp(24), 0);
        EditText key = new EditText(this); key.setSingleLine(true);
        key.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        key.setHint("Новый API-ключ (пусто — не менять)"); layout.addView(key);
        EditText model = new EditText(this); model.setSingleLine(true); model.setHint("Модель Gemini");
        model.setText(AppSettings.model(this)); layout.addView(model);
        new AlertDialog.Builder(this).setTitle("Настройки Gemini")
                .setView(layout).setPositiveButton("Сохранить", (dialog, which) -> {
                    String modelName = model.getText().toString().trim();
                    if (!AppSettings.validModel(modelName)) { updateStatus("Некорректное имя модели."); return; }
                    try {
                        String newKey = key.getText().toString().trim();
                        if (!newKey.isEmpty()) secrets.save(newKey);
                        if (!AppSettings.saveModel(this, modelName)) throw new IllegalStateException("Не удалось сохранить модель");
                        updateStatus("Настройки сохранены.");
                    } catch (Exception e) { updateStatus("Не удалось безопасно сохранить ключ на этом устройстве."); }
                }).setNeutralButton("Удалить ключ", (dialog, which) -> { secrets.clear(); updateStatus("Ключ удалён."); })
                .setNegativeButton("Отмена", null).show();
    }
    private void clearConversation() {
        if (busy) return;
        while (history.length() > 0) history.remove(history.length() - 1);
        conversation.setText(""); updateStatus("Новый диалог.");
        if (tts != null) tts.stop();
    }
    private void append(String role, String text) {
        if (conversation.length() > 0) conversation.append("\n\n");
        conversation.append(role + ": " + text);
        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
    }
    private JSONObject turn(String role, String text) throws Exception {
        return new JSONObject().put("role", role).put("parts", new JSONArray().put(new JSONObject().put("text", text)));
    }
    private void submit() {
        if (busy || listening) return;
        String question = input.getText().toString().trim();
        if (question.isEmpty()) return;
        final String apiKey;
        try { apiKey = secrets.load(); }
        catch (Exception e) { updateStatus("Не удалось прочитать ключ. Удалите его и сохраните заново."); return; }
        if (apiKey.isEmpty()) { updateStatus("Добавьте API-ключ в настройках."); return; }
        if (tts != null) tts.stop();
        final JSONArray request;
        try {
            request = new JSONArray(history.toString());
            request.put(turn("user", question));
        } catch (Exception e) { updateStatus("Не удалось подготовить запрос."); return; }
        String model = AppSettings.model(this);
        input.setText(""); append("Вы", question); setBusy(true); updateStatus("Думаю…");
        executor.execute(() -> {
            try {
                String response = client.generate(apiKey, model, request);
                runOnUiThread(() -> {
                    if (destroyed) return;
                    try {
                        history.put(turn("user", question)); history.put(turn("model", response));
                        while (history.length() > 20) { history.remove(0); history.remove(0); }
                    } catch (Exception ignored) { }
                    append("Gemini", response); setBusy(false);
                    updateStatus(ttsReady ? "Готово." : "Готово. Синтез речи недоступен.");
                    if (ttsReady) tts.speak(response, TextToSpeech.QUEUE_FLUSH, null, "answer");
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (destroyed) return;
                    setBusy(false); input.setText(question);
                    String message = e instanceof java.io.IOException && e.getMessage() != null && e.getMessage().startsWith("Gemini API: HTTP")
                            ? e.getMessage() : "Ошибка запроса. Проверьте интернет, WebShift DNS и доступ к Gemini API.";
                    updateStatus(message);
                });
            }
        });
    }
    private void setBusy(boolean value) {
        busy = value; send.setEnabled(!value); microphone.setEnabled(!value); clear.setEnabled(!value);
    }
    private void startVoice() {
        if (busy || listening) return;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, MICROPHONE_REQUEST); return;
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) { updateStatus("Системный распознаватель речи недоступен."); return; }
        if (tts != null) tts.stop();
        try {
            if (recognizer == null) {
                String component = Settings.Secure.getString(getContentResolver(), "voice_recognition_service");
                ComponentName name = component == null ? null : ComponentName.unflattenFromString(component);
                recognizer = name == null ? SpeechRecognizer.createSpeechRecognizer(this) : SpeechRecognizer.createSpeechRecognizer(this, name);
                recognizer.setRecognitionListener(new RecognitionListener() {
                    public void onReadyForSpeech(Bundle params) { updateStatus("Слушаю…"); }
                    public void onBeginningOfSpeech() { }
                    public void onRmsChanged(float rmsdB) { }
                    public void onBufferReceived(byte[] buffer) { }
                    public void onEndOfSpeech() { updateStatus("Распознаю…"); }
                    public void onError(int error) { listening = false; updateStatus("Ошибка распознавания речи (код " + error + "). Попробуйте снова или введите текст."); }
                    public void onResults(Bundle results) {
                        listening = false;
                        ArrayList<String> texts = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                        if (texts == null || texts.isEmpty()) { updateStatus("Речь не распознана."); return; }
                        input.setText(texts.get(0)); submit();
                    }
                    public void onPartialResults(Bundle partialResults) { }
                    public void onEvent(int eventType, Bundle params) { }
                });
            }
            Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU");
            listening = true; updateStatus("Подключаю микрофон…"); recognizer.startListening(intent);
        } catch (RuntimeException e) { listening = false; updateStatus("Не удалось запустить распознавание речи на этой прошивке."); }
    }
    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request == MICROPHONE_REQUEST) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) startVoice();
            else updateStatus("Для голосового ввода нужен доступ к микрофону.");
        }
    }
    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_VOICE_ASSIST || keyCode == KeyEvent.KEYCODE_SEARCH) { startVoice(); return true; }
        return super.onKeyDown(keyCode, event);
    }
    @Override protected void onStop() {
        super.onStop();
        if (recognizer != null && listening) { recognizer.cancel(); listening = false; }
        if (tts != null) tts.stop();
    }
    @Override protected void onDestroy() {
        destroyed = true;
        if (recognizer != null) recognizer.destroy();
        if (tts != null) { tts.stop(); tts.shutdown(); }
        client.cancel(); executor.shutdownNow();
        super.onDestroy();
    }
}
