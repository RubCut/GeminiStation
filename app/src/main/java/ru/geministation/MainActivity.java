package ru.geministation;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.WindowManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public final class MainActivity extends Activity {
    private static final int MICROPHONE_REQUEST = 100;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final ExecutorService speechExecutor = Executors.newSingleThreadExecutor();
    private final AtomicInteger speechGeneration = new AtomicInteger();
    private final VoicePlayer voicePlayer = new VoicePlayer();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable recognitionTimeout;
    private long recognitionStarted, speechEnded;
    private int recognitionAttempt, partialCount;
    private boolean waitingForFinal;
    private final JSONArray history = new JSONArray();
    private GeminiClient client;
    private SecretStore secrets;
    private SpeechRecognizer recognizer;
    private TextView status, conversation;
    private ScrollView scroll;
    private EditText input;
    private Button send, microphone, clear;
    private boolean busy, listening, destroyed, resumed;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        secrets = new SecretStore(this);
        client = new GeminiClient();
        buildUi();
        try {
            startForegroundService(new Intent(this, WebServerService.class));
        } catch (RuntimeException e) { updateStatus("Не удалось запустить веб-сервер."); }
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
        root.setPadding(dp(40), dp(18), dp(40), dp(12));
        GradientDrawable panel = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xf22c2345, 0xfa141321});
        panel.setCornerRadii(new float[]{dp(24), dp(24), dp(24), dp(24), 0, 0, 0, 0});
        root.setBackground(panel);
        TextView title = new TextView(this);
        title.setText("✦  Gemini"); title.setTextSize(23); title.setTextColor(Color.WHITE);
        root.addView(title);
        status = new TextView(this); status.setTextColor(0xffb7c8e3); status.setTextSize(16);
        updateStatus("Скажите запрос или введите его текстом. Для начала добавьте API-ключ в настройках.");
        root.addView(status);
        scroll = new ScrollView(this);
        conversation = new TextView(this); conversation.setTextColor(Color.WHITE); conversation.setTextSize(19);
        conversation.setTextIsSelectable(false); conversation.setPadding(0, dp(12), 0, dp(12));
        scroll.addView(conversation);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, dp(104)));
        input = new EditText(this); input.setSingleLine(true); input.setTextColor(Color.WHITE);
        input.setHintTextColor(0xff9baac0); input.setHint("Ваш вопрос");
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setOnEditorActionListener((v, action, event) -> { submit(); return true; });
        root.addView(input);
        LinearLayout actions = new LinearLayout(this);
        microphone = button("Говорить", v -> { if (listening) finishVoiceInput(); else startVoice(); });
        send = button("Отправить", v -> submit());
        clear = button("Новый диалог", v -> clearConversation());
        Button settings = button("Настройки", v -> showSettings());
        Button web = button("Веб", v -> showWebInfo());
        actions.addView(microphone); actions.addView(send); actions.addView(clear); actions.addView(settings); actions.addView(web);
        root.addView(actions);
        setContentView(root);
        getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        getWindow().setGravity(Gravity.BOTTOM);
        getWindow().setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT);
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
                .setMessage(state + "\n\nАдрес: " + WebServerService.address() + "\n\nБез пароля: любой в локальной сети может менять настройки. HTTP не шифруется. Лучше использовать ADB-проброс.")
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
        stopSpeaking();
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
        stopSpeaking();
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
                    if (resumed) speak(apiKey, response); else updateStatus("Готово.");
                });
            } catch (Exception e) {
                AppLog.add("Текст: ошибка обработки " + e.getClass().getSimpleName());
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
    private void stopSpeaking() {
        speechGeneration.incrementAndGet();
        voicePlayer.stop();
        client.cancelSpeech();
    }
    private void speak(String apiKey, String text) {
        final int generation = speechGeneration.incrementAndGet();
        updateStatus("Генерирую голос Gemini…");
        speechExecutor.execute(() -> {
            try {
                PcmAudio audio = client.synthesize(apiKey, text);
                if (speechGeneration.get() != generation) return;
                runOnUiThread(() -> { if (!destroyed && speechGeneration.get() == generation) updateStatus("Отвечаю…"); });
                AppLog.add("TTS: начало воспроизведения");
                voicePlayer.play(audio, () -> speechGeneration.get() == generation);
                AppLog.add("TTS: воспроизведение завершено или прервано");
                runOnUiThread(() -> { if (!destroyed && speechGeneration.get() == generation) updateStatus("Готово."); });
            } catch (Exception e) {
                AppLog.add("TTS: ошибка обработки " + e.getClass().getSimpleName());
                runOnUiThread(() -> {
                    if (!destroyed && speechGeneration.get() == generation) {
                        String detail = e instanceof java.io.IOException && e.getMessage() != null && e.getMessage().startsWith("Gemini TTS: HTTP")
                                ? " (" + e.getMessage() + ")" : "";
                        updateStatus("Не удалось озвучить ответ через Gemini" + detail + ". Текст доступен на экране.");
                    }
                });
            }
        });
    }
    private void setBusy(boolean value) {
        busy = value; send.setEnabled(!value); microphone.setEnabled(!value); clear.setEnabled(!value);
    }
    private long recognitionElapsed() { return SystemClock.elapsedRealtime() - recognitionStarted; }
    private void clearRecognitionTimeout() {
        if (recognitionTimeout != null) handler.removeCallbacks(recognitionTimeout);
        recognitionTimeout = null;
    }
    private void finishRecognition() {
        clearRecognitionTimeout();
        listening = false;
        waitingForFinal = false;
        microphone.setText("Говорить");
        microphone.setEnabled(!busy);
    }
    private void scheduleRecognitionTimeout(final int attempt) {
        clearRecognitionTimeout();
        recognitionTimeout = () -> {
            if (!listening || attempt != recognitionAttempt) return;
            AppLog.add("Речь #" + attempt + ": нет финального результата 15 с после завершения фразы; всего " + recognitionElapsed() + " мс");
            recognitionAttempt++;
            finishRecognition();
            if (recognizer != null) { recognizer.cancel(); recognizer.destroy(); recognizer = null; }
            updateStatus("Распознаватель не ответил. Повторите или отправьте предварительный текст вручную.");
        };
        handler.postDelayed(recognitionTimeout, 15000);
    }
    private void finishVoiceInput() {
        if (!listening || waitingForFinal || recognizer == null) return;
        waitingForFinal = true;
        speechEnded = SystemClock.elapsedRealtime();
        microphone.setEnabled(false);
        AppLog.add("Речь #" + recognitionAttempt + ": ручное завершение через " + recognitionElapsed() + " мс");
        updateStatus("Распознаю…");
        scheduleRecognitionTimeout(recognitionAttempt);
        try { recognizer.stopListening(); }
        catch (RuntimeException e) {
            AppLog.add("Речь: ошибка stopListening " + e.getClass().getSimpleName());
            recognitionAttempt++; finishRecognition();
            if (recognizer != null) { recognizer.destroy(); recognizer = null; }
            updateStatus("Не удалось завершить распознавание. Попробуйте ещё раз.");
        }
    }
    private void startVoice() {
        if (busy || listening) return;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            AppLog.add("Речь: запрашиваем разрешение микрофона");
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, MICROPHONE_REQUEST); return;
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) { updateStatus("Системный распознаватель речи недоступен."); return; }
        stopSpeaking();
        final int attempt = ++recognitionAttempt;
        recognitionStarted = SystemClock.elapsedRealtime(); speechEnded = 0; partialCount = 0;
        try {
            String component = Settings.Secure.getString(getContentResolver(), "voice_recognition_service");
            ComponentName name = component == null ? null : ComponentName.unflattenFromString(component);
            if (recognizer == null) {
                recognizer = name == null ? SpeechRecognizer.createSpeechRecognizer(this) : SpeechRecognizer.createSpeechRecognizer(this, name);
            }
            AppLog.add("Речь #" + attempt + ": запуск; служба " + (name == null ? "системная по умолчанию" : name.flattenToShortString()));
            recognizer.setRecognitionListener(new RecognitionListener() {
                private boolean active() { return listening && recognitionAttempt == attempt; }
                public void onReadyForSpeech(Bundle params) {
                    if (!active()) return;
                    AppLog.add("Речь #" + attempt + ": микрофон готов через " + recognitionElapsed() + " мс");
                    if (!waitingForFinal) updateStatus("Слушаю… Нажмите «Готово» после фразы.");
                }
                public void onBeginningOfSpeech() { if (active()) AppLog.add("Речь #" + attempt + ": начало речи через " + recognitionElapsed() + " мс"); }
                public void onRmsChanged(float rmsdB) { }
                public void onBufferReceived(byte[] buffer) { }
                public void onEndOfSpeech() {
                    if (!active()) return;
                    if (speechEnded == 0) speechEnded = SystemClock.elapsedRealtime();
                    waitingForFinal = true; microphone.setEnabled(false);
                    AppLog.add("Речь #" + attempt + ": конец фразы через " + recognitionElapsed() + " мс");
                    updateStatus("Распознаю…");
                    scheduleRecognitionTimeout(attempt);
                }
                public void onError(int error) {
                    if (!active()) return;
                    AppLog.add("Речь #" + attempt + ": ошибка " + error + " (" + recognitionErrorName(error) + ") через " + recognitionElapsed() + " мс");
                    finishRecognition();
                    updateStatus("Ошибка распознавания речи (код " + error + "). Попробуйте снова или введите текст.");
                }
                public void onResults(Bundle results) {
                    if (!active()) return;
                    long finalDelay = speechEnded == 0 ? -1 : SystemClock.elapsedRealtime() - speechEnded;
                    ArrayList<String> texts = results == null ? null : results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    AppLog.add("Речь #" + attempt + ": финальный результат через " + recognitionElapsed()
                            + " мс; после конца речи " + (finalDelay < 0 ? "неизвестно" : finalDelay + " мс")
                            + "; частичных результатов " + partialCount + "; вариантов " + (texts == null ? 0 : texts.size()));
                    finishRecognition();
                    if (texts == null || texts.isEmpty() || texts.get(0) == null || texts.get(0).trim().isEmpty()) { updateStatus("Речь не распознана."); return; }
                    input.setText(texts.get(0)); submit();
                }
                public void onPartialResults(Bundle partialResults) {
                    if (!active() || partialResults == null) return;
                    ArrayList<String> texts = partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    if (texts == null || texts.isEmpty() || texts.get(0) == null) return;
                    partialCount++;
                    if (partialCount == 1) AppLog.add("Речь #" + attempt + ": первый промежуточный результат через " + recognitionElapsed() + " мс");
                    input.setText(texts.get(0));
                }
                public void onEvent(int eventType, Bundle params) { }
            });
            Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU");
            intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
            // These are hints; vendor recognition services may ignore them.
            intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 750L);
            intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 600L);
            intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
            listening = true; waitingForFinal = false; microphone.setText("Готово");
            updateStatus("Подключаю микрофон…"); recognizer.startListening(intent);
        } catch (RuntimeException e) {
            AppLog.add("Речь #" + attempt + ": ошибка запуска " + e.getClass().getSimpleName());
            finishRecognition();
            updateStatus("Не удалось запустить распознавание речи на этой прошивке.");
        }
    }
    private static String recognitionErrorName(int error) {
        switch (error) {
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT: return "таймаут сети";
            case SpeechRecognizer.ERROR_NETWORK: return "сеть";
            case SpeechRecognizer.ERROR_AUDIO: return "аудио";
            case SpeechRecognizer.ERROR_SERVER: return "сервер";
            case SpeechRecognizer.ERROR_CLIENT: return "клиент";
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT: return "нет речи";
            case SpeechRecognizer.ERROR_NO_MATCH: return "не распознано";
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY: return "служба занята";
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS: return "нет прав";
            default: return "неизвестно";
        }
    }
    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request == MICROPHONE_REQUEST) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) startVoice();
            else updateStatus("Для голосового ввода нужен доступ к микрофону.");
        }
    }
    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_VOICE_ASSIST || keyCode == KeyEvent.KEYCODE_SEARCH) { if (listening) finishVoiceInput(); else startVoice(); return true; }
        return super.onKeyDown(keyCode, event);
    }
    @Override protected void onResume() { super.onResume(); resumed = true; }
    @Override protected void onStop() {
        resumed = false;
        super.onStop();
        if (recognizer != null && listening) {
            AppLog.add("Речь #" + recognitionAttempt + ": отменено при уходе с экрана через " + recognitionElapsed() + " мс");
            recognitionAttempt++; recognizer.cancel(); finishRecognition();
        }
        stopSpeaking();
    }
    @Override protected void onDestroy() {
        destroyed = true;
        clearRecognitionTimeout();
        if (recognizer != null) recognizer.destroy();
        stopSpeaking();
        client.cancel(); executor.shutdownNow(); speechExecutor.shutdownNow();
        super.onDestroy();
    }
}
