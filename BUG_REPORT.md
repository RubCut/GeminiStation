# Найденные баги — Gemini Station (только список, ничего не исправлялось)

Статический обзор ветки `arena/0521582d-geministation` (HEAD `aef857e`): Java-код приложения,
веб-панель `assets/index.html`, `buttonhelper`, `pluginrunner`, CI. Часть проверял запуском
(`node tools/test_voice_settings.js`, `test_home_rooms.js`, `test_led_editor.js`, `test_plugin_ui.js` —
все проходят). JVM/Gradle-тесты в песочнице не запускались (нет Android SDK/сети к Maven), поэтому
это статический разбор, а не подтверждение на устройстве.

Формат: симптом → причина со ссылкой → почему это баг. Severity: 🔴 высокая, 🟠 средняя, 🟡 низкая.

---

## 1. 🔴 Превью «своей» GIF в панели всегда 404 (это не косметика)

`index.html:783`:

```js
function ledPreview(mode,style){el('ledPreview-'+mode).src='/api/led/preview/'+style+'/'+mode+(style==='custom'?'?v='+Date.now():'')}
```

Сервер для кастомных превью сравнивает версию строго с hex-хешем (`WebServerService.java:213`):

```java
if (versions != null && (versions.size() != 1 || !LedAssetUrl.build(mode, ...).endsWith("?v=" + versions.get(0))))
    return json(Response.Status.NOT_FOUND, "Анимация уже изменилась");
```

`LedAssetUrl.build()` даёт `?v=<sha256, 16 hex>`. Панель присылает `?v=1730…` (миллисекунды) →
`endsWith` всегда `false` → **любое** превью `custom` после выбора/загрузки возвращает
`404 {"message":"Анимация уже изменилась"}`. На картинке — «битая» иконка вместо анимации.
При этом `test_led_editor.js:66` проверяет только формат `src` (`^/api/led/preview/custom/thinking\?v=`),
поэтому тест этого не ловит.

Дополнительно: `pages`/`refresh` не инвалидируют `<img src>`, т.е. после смены стиля превью
«new/classic/hybrid» перезагружается только по нажатию, а `select` в панели (`index.html:791`)
вызывает `ledPreview` без `?v=` для некастомных — кэш браузера может показать старую картинку.

---

## 2. 🔴 `deleteKey` в `/api/config` стирает ВСЕ ключи, не один

`WebServerService.java:753-757`:

```java
boolean deleteKey = data.optBoolean("deleteKey", false);
...
if (deleteKey) vault.clear();
```

`ApiKeyVault.clear()` (`ApiKeyVault.java:52`) делает `store.clear()` — удаляет весь список до 10 ключей.
Тип поля даже не проверяется (`optBoolean` вернёт `true` для строки `"true"`), в отличие от всех
остальных флагов в этом же обработчике, где стоит `data.opt(...) instanceof Boolean`. Панель сейчас
удаляет ключи через `/api/keys` (`index.html:881-887`), но любой старый клиент/скрипт, пославший
`deleteKey:true`, потеряет все ключи без подтверждения и без указания, какой именно удалять.
`index.html:704` до сих пор передаёт это поле (всегда `false`) — то есть контракт живой.

---

## 3. 🔴 Удаление токенов через `SecretStore.clear()` — `apply()`, а логика ждёт синхронности

`SecretStore.java:54`:

```java
void clear() { synchronized (LOCK) { ...edit().clear().apply(); } }
```

тогда как запись — `commit()` (`SecretStore.java:42`). `clear()` вызывается из трёх мест, где сразу
после него читают состояние:

- `WebServerService.java:441` — «Удалить доступ к умному дому»: `store.clear()`, лок отпущен,
  следующий `/api/home/rooms` или `SmartHome.identity()` может прочитать ещё не стёртое значение →
  `identity` совпадёт, и `HomeSelection.allowed()` пустит к комнатам уже «удалённого» аккаунта.
- `WebServerService.java:628` — «Удалить настройки LED» (`deleted`), затем `led.invalidate()` +
  `prewarm()`; `prewarm` читает `new SecretStore(context,"led_secrets").load()` и может успеть
  взять старый токен → лишний запрос к Яндексу уже после «удаления».
- `PersonalWakeStore.clear()` (`PersonalWakeStore.java:41`) — сразу за ним
  `WakeWordController.personalSamplesChanged()` → `reconcile()` → `PersonalWakeStore.load()`:
  личные образцы могут «прочитаться» ещё раз до фактического стирания.

---

## 4. 🔴 Смертельный дедлок-риск: сетевой запрос под глобальным `HomeSelection.LOCK`

`SmartHome.setCapability` (`SmartHome.java:187-215`):

```java
synchronized (HomeSelection.LOCK) {
    ...
    JSONObject current = YandexCloud.home(token, "/v1.0/user/info", null);  // до 8 с callTimeout
    ...
    result = YandexCloud.home(token, "/v1.0/devices/actions", body);        // ещё до 8 с
}
```

Тот же `HomeSelection.LOCK` держат:

- `WebServerService.java:415-427` (`POST /api/home/rooms`) — **внутри** делает
  `SmartHome.loadFull()` (сетевой запрос) и потом `HomeSelection.save()`;
- `WebServerService.java:441`, `456` (удаление/сохранение доступа);
- `HomeSelection.save()` (`HomeSelection.java:43`).

Пока идёт голосовая команда умному дому (два сетевых вызова, до ~16 с), веб-панель «Сохранить
комнаты» висит на этом же мониторе, а `NanoHTTPD`-поток заблокирован. Обратный сценарий: панель
держит лок на время `loadFull()` → голосовая команда ждёт. Плюс внутри лока трижды пересчитывается
`identity(c)` (SHA-256 + чтение prefs).

---

## 5. 🟠 «Нет речи» и «таймаут подключения» могут оба пропасть в Gemini Live

`MainActivity.java:646-660`:

```java
public void onReady() {
    ...
    if (liveConnected && !heardSpeech && !waitingForFinal) scheduleNoSpeechTimeout(...);
}
public void onConnected() {
    liveConnected = true;
    if (!waitingForFinal) clearRecognitionTimeout();          // снимает 20-секундный таймаут
    if (micReady && !heardSpeech && !waitingForFinal) scheduleNoSpeechTimeout(...);
}
```

Оба колбэка приходят на main-thread, но порядок задаёт `LiveTranscriber`:
`onConnected` постится из `setupComplete` (`LiveTranscriber.java:78`), `onReady` — из потока захвата
(`LiveTranscriber.java:127`). При `earlyCapture=true` микрофон стартует раньше, поэтому `onReady`
может прийти **первым**, когда `liveConnected == false` → таймер не ставится; затем приходит
`onConnected`, у которого `micReady` уже `true` → таймер всё же ставится. А вот в обратном порядке
(`onConnected` первым, `micReady` ещё `false`) `clearRecognitionTimeout()` снимает таймаут подключения,
`onReady` ставит silence-таймер… **если** `liveConnected` успел стать `true`. Между этими двумя
проверками есть окно, где оба условия ложны и **никакого** таймера не остаётся: диалог висит с
«Слушаю…» бесконечно (до ручного «назад»). Проверить на устройстве трудно, но код допускает.

---

## 6. 🟠 `retrySafe` для Gemini Live считается по запуску потока, а не по отправленному аудио

`LiveTranscriber.java:286-292`:

```java
boolean retrySafe = !captureStarted.get();
```

`captureStarted` выставляется в `maybeStartCapture()` **до** открытия `AudioRecord`
(`LiveTranscriber.java:107-112`). При `earlyCapture=false` и обрыве WebSocket во время речи
пользователя: аудио уже ушло в сокет (или лежит в `pendingAudio`), но `captureStarted=true` → верно
`retrySafe=false`, ошибка. А при `earlyCapture=true` и обрыве **до** `setupComplete`:
`captureStarted` уже `true`, аудио в буфере `PendingAudio` (оно будет выброшено `cancel()`), но
`retrySafe=false` → перезапуск с новым ключом не выполняется, хотя фраза ещё никуда не ушла.
Нужен отдельный флаг «отправлен хотя бы один `realtimeInput`-чанк».

---

## 7. 🟠 `AudioStreamBuffer.connect()` отправляет накопленное под монитором

`AudioStreamBuffer.java:15-23`:

```java
synchronized int connect(Sink sink) throws Exception {
    connected = true;
    while (!pending.isEmpty()) sink.audio(pending.poll());   // webSocket.send() под локом
    ...
}
```

`write()` из потока микрофона (`LiveTranscriber.capture`) берёт тот же монитор
(`AudioStreamBuffer.java:11`), поэтому на всё время «выстреливания» буфера (до 10 с аудио,
`MAX_PENDING_BYTES = 16000*2*10`, `LiveTranscriber.java:35`) запись блокируется: `AudioRecord.read()`
не вызывается, во входном буфере дропается/переполняется звук → дыры в распознавании ровно на
границе подключения. Снимок очереди под локом и отправка вне лока решают это.

---

## 8. 🟠 `VoicePlayer` считает `AudioTrack.write() == 0` ошибкой

`VoicePlayer.java:56` и `VoicePlayer.java:100`:

```java
int count = track.write(...);
if (count <= 0) throw new IOException("Audio playback failed");
```

`AudioTrack.write()` в `MODE_STREAM` штатно может вернуть `0` при заполненном буфере (особенно на
медленном TV-тракте) — это сигнал «повтори позже», а не сбой. Итог: потоковый TTS обрывается с
«Синтез Gemini не удался» / «Audio playback failed» на нормальном железе, после чего срабатывает
2-секундный `speechFailed`-путь. Правильно — ретраить `0`, падать только на отрицательных кодах.

---

## 9. 🟠 После `clear()` LED планировщик «закрыть через 10 с» и `prewarm()` «через 30 с» делят один `idleClose`

`LedController.java:83-87` и `LedController.java:157-162`:

```java
// clear()
idleClose = () -> executor.execute(() -> { if (version == generation && connection != null) connection.close(); });
main.postDelayed(idleClose, 10000);

// prewarm() при active == null
if (idleClose != null) main.removeCallbacks(idleClose);   // ← это есть
idleClose = ...;
main.postDelayed(idleClose, 30000);
```

`prewarm` снимает callback корректно, а вот `show(next)` → `change()` (`LedController.java:89-95`)
снимает `idleClose`, но **не** `retry`-связку с version-проверкой — там `retry` снимается верно.
Реальная проблема: `clear()` планирует закрытие соединения через 10 с, а `dispatch()` при этом
может ещё держать `inFlight`; `close()` из `idleClose` выполняется в executor **после** того, как
`dispatch` положит `inFlight=false` и `main.post` вызовет `dispatch()` заново — есть окно, где
новый кадр уйдёт в уже закрытое соединение и будет один лишний полный retry (3 × 3 с). Не критично,
но лишние 9 с без LED.

---

## 10. 🟠 `LedController.playMusic` рапортует «успех» до любого ответа станции

`LedController.java:304`:

```java
main.postDelayed(() -> pending.report(true), 1000);
```

`report(true)` означает «команда принята», хотя никакого подтверждения от станции нет (в лог честно
пишется «воспроизведение не подтверждено»). Если станция ответит ошибкой на 1.2 с (реальный ответ
идёт через `recordMusicReply`), `pending.report(true)` уже отстрелялся (CAS), и `MusicDiagnostics`
ничего не может сделать — ошибка видна только в журнале станции. UI показывает «включено», хотя
`MusicDiagnostics` вернул `error`. Порог 1 с не связан ни с таймаутом локального `wss`
(`LedConnection` ждёт до 6 с), ни с реальным временем ответа.

---

## 11. 🟠 `WakeWordController`: если захват умер сам — активация остаётся мёртвой

`WakeWordController.java:118-130`:

```java
} else if (!firing.get() && wanted()) {
    status = "Обработчик остановился; выключите и снова включите активацию";
    AppLog.add("Голосовая активация: обработчик неожиданно остановился");
}
```

Нет ни `reconcile()`, ни повторного `start()`. `WakeCapture.capture()` завершается штатно (не по
`stopping`), если `AudioRecord.read()` вернул `count == 0` и `Thread.sleep(20)` прерывается, или
`recorder` отдаёт ошибку, не приводящую к исключению. После этого `capture == null`, а повторной
попытки нет: голосовой вызов не работает до ручного вмешательства. При этом `dialogClosed()` /
`settingsChanged()` вызовут `reconcile()` только при следующем событии.

---

## 12. 🟠 `stopAndWait()` блокирует единственный worker до 4 с

`WakeCapture.java:151-158` (`thread.join(4000)`) вызывается из `WakeWordController.stopCapture()`,
а тот — из `worker` (один поток, `WakeWordController.java:19`). Пока идёт join, в очередь встают
`pauseForDialog` (из `MainActivity.onResume`), `settingsChanged`, `personalSamplesChanged`,
`dialogOpening`. `MainActivity` ждёт `continuation` от `pauseForDialog` и до этого не запускает
распознавание → задержка старта диалога до 4 с, а при `join`-таймауте ещё и «Не удалось освободить
микрофон» → диалог вообще не начинается. Заметно, что `dialogClosed()` уже содержит
`main.postDelayed(..., 1000)` — защита от этого же места.

---

## 13. 🟠 `WebSecurity.stationLoopback` не принимает IPv4-mapped IPv6

`WebSecurity.java:24`:

```java
static boolean stationLoopback(String remoteIp) { return "127.0.0.1".equals(remoteIp); }
```

`NanoHTTPD` отдаёт адрес так, как его вернул сокет. На части прошивок/при `adb forward` через IPv6
приходит `::ffff:127.0.0.1` (или `0:0:0:0:0:ffff:7f00:1`). Тогда:

- `PanelAccessPolicy.stationLedAsset` вернёт `false` → `GET /led/*.gif` попадёт под авторизацию панели
  → если пароль включён, станция получит `401` и LED не работает вообще;
- `WebServerService.java:201` (`if (!WebSecurity.stationLoopback(...)) return 403`) — станция
  «стучится» на `/led/...gif` и получает `403 «Только локально»` в логах, хотя это она и есть.

Симптом для пользователя: «LED перестал работать после включения пароля панели».

---

## 14. 🟠 `WebSecurity.permittedWrite` требует `Origin` байт-в-байт `http://<Host>`

`WebSecurity.java:25-28`:

```java
return permittedHost(host) && origin != null && origin.equalsIgnoreCase("http://" + host) && "1".equals(marker);
```

`host` приходит из заголовка `Host`, `origin` — из `Origin`. Совпадают они не всегда:

- панель, открытая по имени хоста (`http://station.local:6529`), не пройдёт `permittedHost`
  (там только `localhost`/`127.0.0.1` и IPv4-адреса интерфейсов, `WebSecurity.java:10-22`);
- `Origin` не отправляется для same-origin `GET`, но для `POST` с `fetch` — отправляется; при
  открытии по `http://192.168.x.x:6529` всё ок, а по `http://[::1]:6529` — нет;
- если когда-нибудь появится реверс-прокси (или панель откроют через preview-домен), `Origin`
  будет `https://…` → все POST дают `403 «Запрос отклонён»` без пояснения, а `GET /api/logs`
  работает → выглядит как «сохранение сломано, а настройки читаются».

---

## 15. 🟠 `MainActivity.onStop()` закрывает диалог при любом уходе с экрана

`MainActivity.java:729-739`:

```java
if (listening) AppLog.add(...);
soundCuePlayer.cancel();
cancelRecognition(); stopSpeaking(); ...
if (!permissionPending && !isChangingConfigurations()) finish();
```

`permissionPending` покрывает только запрос `RECORD_AUDIO`. Любое другое системное окно поверх
(диалог подтверждения установки плагина, разрешение специальных возможностей, экран блокировки,
переключение ввода) даёт `onStop` → `finish()`. Пользователь возвращается — диалога нет, хотя он
его не закрывал. Это же ломает сценарий `PluginApprovalActivity`, открытой из веб-панели во время
диалога.

---

## 16. 🟠 `wakeResetMs` — настройка, которая ничего не делает

`WakeAudioFilter.resetForSilence()` / `resetForSilence(int)` (`WakeAudioFilter.java:64-65`) не
вызываются нигде (`grep -rn resetForSilence app/src/main/java` → только объявления). При этом
`VoiceTuning.wakeResetMs` валидируется (`VoiceTuning.java:17`), сохраняется, попадает в бэкап и —
через `sameWake()` (`VoiceTuning.java:48-50`) — **перезапускает микрофон** при изменении, то есть
пользователь видит реальный эффект (детектор перезапустился), но на поведение сброса это не влияет.
Документация это признаёт, а UI даёт крутить «живую» настройку.

---

## 17. 🟠 `SoundCuePlayer.cancel()` не останавливает уже идущий `VoicePlayer.play()`

`SoundCuePlayer.java:56-77`: в `playActivation` создаётся `AtomicBoolean active`, и
`cueVoice.play(cue, active::get, ...)` крутится в `cueWorker` до конца воспроизведения.
`cancel()` (`SoundCuePlayer.java:127-131`) вызывает `release()` → `activationPlaying.set(false)` +
`cueVoice.stop()`, а `stop()` делает только `pause()+flush()` (`VoicePlayer.java:15-21`). Внутри
`VoicePlayer.play()` цикл `while (written < ... && active.getAsBoolean())` корректно завершится, но
следующий блок ожидания `getPlaybackHeadPosition()` (`VoicePlayer.java:104-107`) после `pause()`
не продвигает позицию → ждём до `deadline` (длительность + 5 с). `MainActivity.onStop()` вызывает
`soundCuePlayer.cancel()` и сразу `finish()` → поток ещё до 5 с держит `AudioTrack` в
`USAGE_ASSISTANT` и глушит другие звуки после закрытия диалога.

---

## 18. 🟠 `MediaCommands.send` не гарантирует `ACTION_UP`

`MediaCommands.java:28-33`:

```java
sink.send(command.keyCode, true);
sink.send(command.keyCode, false);
return true;
} catch (RuntimeException e) { return false; }
```

Если второй `dispatchMediaKeyEvent` бросит (или вернёт исключение внутри `AudioManager`), плеер
останется в состоянии «кнопка зажата»: следующий `play/pause` может отработать как long-press
(seek/assistant). Нужен `try/finally` с UP. Сейчас `DeviceActions.media()` (`DeviceActions.java:71-84`)
трактует `false` просто как «не удалось отправить», а не «отправили половину».

---

## 19. 🟠 `DeviceActions.volume` не учитывает минимальную громкость потока

`DeviceActions.java:93-100`:

```java
int target = ...;
target = Math.max(0, Math.min(max, target));
audio.setStreamVolume(AudioManager.STREAM_MUSIC, target, AudioManager.FLAG_SHOW_UI);
```

`AudioManager.getStreamMinVolume()` не используется. На прошивках, где `minVolume > 0`
(`TV/автомобильные`), `target = 0` даёт `IllegalArgumentException` → внешний
`catch (RuntimeException)` в `prepare()` (`DeviceActions.java:66`) вернёт «Не удалось выполнить
команду на устройстве» вместо «громкость не изменилась»/реального минимума. Плюс `adjust_volume`
считает шаг как `Math.round(max * .1f)` и округляет в 0 при малом `max`, т.е. «громче» может
ничего не сделать, но ответ будет «изменена громкость».

---

## 20. 🟠 `PluginExecution`: таймаут 45 с не отменяет зависший вызов runner'а

`PluginExecution.java:70-125`. `finished.await(remaining)` ждёт `IPluginResult.completed()` или
`onServiceDisconnected`/`onBindingDied`. Если Python-код висит в `while True:` / блокирующем
`adb.run`, `completed()` не придёт, `await` вернёт `false` → «Время выполнения плагина истекло»,
дальше `finally` вызывает `runner.get().cancel()` (→ `killProcess`). Т.е. по таймауту всё же
убивается — **но**: `cancel()` вызывается через Binder; если сам Binder-вызов `executeWithConfig`
ещё не вернулся (Chaquopy держит binder-поток), `cancel()` встанет в очередь и не выполнится до
конца `executeWithConfig`, т.е. «убийство» может не сработать вообще. Таймаут на стороне
`PluginService` (`PluginService.java` — там нет watchdog'а на поток Python) отсутствует.

---

## 21. 🟠 `PluginService` убивает процесс до гарантированной доставки результата

`pluginrunner/.../PluginService.java:44-50`:

```java
String output = ... callAttr(...);
result.completed(output, true);
} catch (...) { result.completed("Plugin execution failed", false); }
finally { Process.killProcess(Process.myPid()); }
```

`IPluginResult.completed` — oneway-подобный binder-callback (`IPluginResult.aidl`), доставка
асинхронна; `killProcess` сразу после вызова может убить процесс раньше, чем транзакция уйдёт в
ядро/получателю. На практике `PluginExecution` тогда получает `onServiceDisconnected` →
`finish.countDown()` без `output` → сообщение «Python runner завершился без результата» вместо
результата плагина. Гонка редкая, но полностью в руках планировщика.

Дополнительно: `busy` (`PluginService.java:22,41`) после `compareAndSet(false,true)` никогда не
сбрасывается в `false` — полагаться на `killProcess` как на «reset» хрупко: если `killProcess`
не сработает, сервис навсегда отвечает «Plugin request rejected».

---

## 22. 🟠 CI собирает `pluginrunner` без репозитория Chaquopy

`settings.gradle`: `dependencyResolutionManagement { repositories { google(); mavenCentral() } }`,
`build.gradle:3`: `id 'com.chaquo.python' version '16.1.0' apply false`,
`pluginrunner/build.gradle:1-20` применяет плагин и `chaquopy { defaultConfig { version = '3.11' } }`.
Артефакты Chaquopy (плагин и `python`-target) не публикуются в Maven Central; нужен
`maven { url 'https://chaquo.com/maven' }` (и в `pluginManagement.repositories`). В CI шаг
`./gradlew ... :pluginrunner:testDebugUnitTest :pluginrunner:assembleDebug` из-за этого
нестабилен/падает на чистом раннере. Локально не проверял (нет сети к chaquo.com).

---

## 23. 🟡 `/api/fonts/google-sans.ttf` отдаёт `InputStream.available()` как Content-Length

`WebServerService.java:177`:

```java
Response response = newFixedLengthResponse(Response.Status.OK, "font/ttf", font, font.available());
```

Для `AssetInputStream` `available()` — «сколько доступно без блокировки», не размер файла.
Возможен обрезанный шрифт (`@font-face` тихо падает на системный) или, наоборот, ответ, который
`NanoHTTPD` не досчитает. Правильно — скопировать в `byte[]` и отдать `length`.

---

## 24. 🟡 `/api/wake-samples` читает тело напрямую из `session.getInputStream()`

`WebServerService.java:520-528`:

```java
byte[] wav = new byte[(int) length];
InputStream input = session.getInputStream();
while (offset < wav.length) { int count = input.read(...); if (count <= 0) return BAD_REQUEST; ... }
```

Тело читается **в обход** `parseBody()`, но `content-length` берётся из заголовка без проверки, что
`NanoHTTPD` ещё не буферизовал его. Если клиент пришлёт `Expect: 100-continue` (браузер так делает
для крупных `fetch` на некоторые конфигурации) или `content-length` окажется больше фактического,
`read()` будет ждать до таймаута сокета NanoHTTPD — панель «висит» на загрузке образца. Лимит
`PersonalWakeStore.MAX_BYTES` (1 МБ) есть, но таймаута на это чтение нет.

---

## 25. 🟡 `YandexQrLogin.qr()` может превысить лимит ответа `NanoHTTPD`

`YandexQrLogin.java:128-140`: PNG 300×300 в base64 → строка ~50-120 КБ; в `/api/yandex/start`
она уходит в JSON. `NanoHTTPD` по умолчанию ограничивает размер запроса, а не ответа, так что
формально ок, но на слабых прошивках большая строка + отсутствие `Content-Length` (chunked
отключён — `useGzipWhenAccepted=false`, но chunked для `newFixedLengthResponse` есть) может дать
«неполный JSON» в панели. Плюс `hints` не включает `EncodeHintType.ERROR_CORRECTION`, а при
`value.length() > 2048` `QRCodeWriter` кидает — сообщение пользователю будет общее
«Не удалось выполнить вход».

---

## 26. 🟡 `SseReader.readLine()` ограничивает длину строки на 1 байт позже, чем нужно

`SseReader.java:44-45`:

```java
line.append((char) c);
if (line.length() > MAX_LINE) throw new IOException("SSE line too large");
```

Проверка после `append` — строка успевает вырасти до `MAX_LINE + 1`. Офф-бай-ван, но в
`GeminiClient.synthesizeStreaming` это единственная защита от «бесконечной строки» без `\n`
внутри события; через `SseReader` из `LiveTranscriber` тот же класс, но там кадры ограничены
`MAX_FRAME_BYTES` в `GeminiSocketListener`. Мелочь, но лимит «MAX_LINE» на деле другой.

---

## 27. 🟡 `HttpLimits.readUtf8` сначала смотрит `contentLength()`, потом буферизует

`HttpLimits.java:8-14`:

```java
if (limit < 0 || body.contentLength() > limit) throw ...
source.request((long) limit + 1);
if (source.buffer().size() > limit) throw ...
```

Для chunked-ответа `contentLength() == -1`, и `source.request(limit + 1)` заставит OkHttp
прочитать **всё** тело до EOF (или до заполнения буфера). Отдельной защиты от «бесконечного»
chunked-ответа нет — есть только проверка `size() > limit`, т.е. память уже выделена под лимит.
Для Gemini/яндекса это 1-2 МБ, терпимо, но заявленный «bound before decoding» тут не совсем bound.

---

## 28. 🟡 `WakeCapture`/`MicroWakeDetector`: `PersonalWakeMatcher.history` меняется из capture-потока,
читается из пула

`WakeCapture.capture()` (`WakeCapture.java:104-110`) вызывает `personal.add(now)` →
`PersonalWakeWorker.add()` → `matcher.add(features)` (capture-поток). `snapshot()` вызывается из
`offer()` (тоже capture-поток), но `worker.execute(() -> matcher.best(snapshot))` работает в пуле —
и `best()` только читает уже склонированный снапшот, так что гонки нет. **Однако**
`PersonalWakeWorker.hitAt` (`AtomicLong`) и `nextOffer` (не volatile, не atomic) меняются из
capture-потока, а `takeHit()` вызывается тоже из capture-потока — ок. Итог: явного бага нет, но
`PersonalWakeWorker` легко сломать при любом изменении потоков; помечаю как риск, а не баг.

---

## 29. 🟡 `MicroWakeDetector` переиспользует `features` для VAD и личных образцов

`MicroWakeDetector.java:80-96`: один и тот же массив `features` уходит и в `input` (квантование),
и в `vadInput`, и в `personal.add(features)`. `PersonalWakeMatcher.add` нормализует и копирует, так
что фактической порчи нет, но `features` заполняется `MicroFrontend.process()` только при
`count == 40`; если нативный фронтенд вернёт `0`, массив остаётся от предыдущего кадра, и
`personal.add()` всё равно его получит (вызов идёт после `if (frontend.process(...) == 0) continue;`
— т.е. не получит; ок). Ложная тревога, оставляю как «читается опасно, но корректно».

---

## 30. 🟡 `AppLog` синхронизирован на весь журнал, а `SimpleDateFormat` создаётся на каждую запись

`AppLog.java:11-18`: `synchronized add()` + `new SimpleDateFormat(...)` на каждое событие.
Журнал пишется из audio-потоков (`LiveTranscriber` — `AppLog.add` в цикле по 100 мс-чанкам) и из
сетевых колбэков; при 1500 записях `snapshot()` (`AppLog.java:19-23`) тоже синхронизирован и
копирует всё в `JSONArray` на каждом `/api/logs` (раз в 5 с из панели). На станции с медленным CPU
это заметный jitter в аудиотракте (в логах видно «микрофронтенд+модель X% аудиовремени»).
Не баг логики, но реальный источник задержек.

---

## 31. 🟡 `PanelAuthEngine.login`: блокировка наступает на 5-й попытке, а не после 5-й

`PanelAuthEngine.java:52-63`:

```java
if (state[1] >= 5) return null;
state[1]++;
```

Счётчик увеличивается **до** проверки пароля, поэтому 5-я попытка в окне 60 с всегда
отклоняется, даже с верным паролем. UI/сообщение («превышено число попыток, попробуйте через
минуту») обещает «5 попыток», по факту доступно 4. Мелочь, но поведение не то, что заявлено.

---

## 32. 🟡 `SettingsBackup.restore` дёргает `settingsChanged()` даже когда ничего не записано

`SettingsBackup.java:43`: `} finally { WakeWordController.get(c).settingsChanged(); }` — `finally`
исполняется и при исключении из валидации (до записи). Итог: лишний перезапуск локального
детектора (и возможная потеря первых секунд захвата) на каждый неудачный импорт.

---

## 33. 🟡 `DialogDirective.text()` удаляет маркер, но не выставляет `finish`

`DialogDirective.java:12-21`: если модель написала `[[DIALOG_END]]` в середине текста (не в конце),
`endsWith` не сработает, `finish = false`, а `replace(END, "")` вырежет маркер. Это «безопасно», но
означает: при нестандартном форматировании модель «попросила закончить», а диалог продолжается.
Симметрично: `[[DIALOG_CONTINUE]]` в середине просто исчезает — ок.

---

## 34. 🟡 `ConversationFlow`: провал TTS в фазе `THINKING` не даёт `followUp()`

`MainActivity.speechFailed` (`MainActivity.java:497-506`) планирует `beginFollowUp()`, а тот
(`MainActivity.java:513-521`) вызывает `flow.followUp()`, который требует фазу `SPEAKING`
(`ConversationFlow.java:19-22`). Если `onPlaybackStarted()` так и не был вызван (TTS упал **до**
старта звука), фаза остаётся `THINKING` → `followUp()` вернёт `false` и `return` — диалог
молча висит без микрофона, без ошибки на экране и без закрытия. Воспроизводится при недоступном
Gemini TTS (например, 429 на всех ключах в стриминге). Симптом: «ответ показан, но микрофон больше
не включается, и окно не закрывается».

---

## 35. 🟡 `GET /api/logs` каждые 5 с пересобирает полный JSON

`index.html:890` (`setInterval(()=>refresh().catch(()=>{}),5000)`) → `/api/logs`, который каждый раз
заново формирует `JSONObject` со всеми 1500 записями, `VoiceTuning`, ключами, промптом
(`WebServerService.java:235-259`). На станции это постоянная нагрузка + полный трафик по Wi-Fi,
даже когда панель открыта в фоновой вкладке. Нет `ETag`/`If-None-Match`, нет инкрементального
`?since=`.

---

## 36. 🟡 `WebServerService.address()` не исключает VPN/tun-интерфейсы и не кэшируется

`WebServerService.java:33-44`: возвращает первый `Inet4Address` с любого `isUp`-интерфейса. Если
станция поднята с VPN (`tun0`, `10.8.0.x`), пользователю покажут адрес, недоступный из его сети.
Кэша нет — перебор интерфейсов на каждый вызов. Также не фильтруются `docker0`/`ap0`-подобные
интерфейсы точки доступа.

---

## 37. 🟡 `AndroidTtsPlayer`: `onStop`/`onError` после `stop()` не отменяют таймаут

`AndroidTtsPlayer.java:24-35` (`stop()` → `generation.incrementAndGet()` и `release()`), а
`UtteranceProgressListener` (`AndroidTtsPlayer.java:105-120`) на `onStop` вызывает `fail(...)`,
который делает `generation.compareAndSet(id, id + 1)` — id уже другой, `CAS` не пройдёт, `listener.error`
не вызовется. Это правильно; но `release()` внутри `fail` тоже не выполнится, и если `stop()` не
вызывался (движок сам прервал озвучку), `timeout` (`AndroidTtsPlayer.java:122-124`) остаётся
висеть до `12000+` мс, потом `fail` с `compareAndSet` сработает — то есть при «чужом» `onStop`
мы ждём лишние 30-180 с до срабатывания timeout-фолбэка. Пользователь видит «зависшую» озвучку.

---

## 38. 🟡 `WakeSensitivity`/`MicroWakePolicy`: `Integer.parseInt` без защиты

`MicroWakePolicy.threshold` (`MicroWakePolicy.java:16-21`) делает `Integer.parseInt(WakeSensitivity.normalize(sensitivity))`.
`normalize` гарантирует валидность, так что сейчас ок; но `threshold` вызывается из
`MicroWakeDetector` в конструкторе, и если когда-нибудь `AppSettings.wakeSensitivity` вернёт
«сырое» значение (сейчас `normalize` вызывается и там) — `NumberFormatException` в
`WakeCapture.start()` ловится как `Exception` → «Ошибка запуска». Хрупко, но не баг сегодня.

---

## 39. 🟡 `index.html`: `request()` при `401` продолжает выполнение после `location.reload()`

`index.html:~437`:

```js
const data=await response.json(); if(response.status===401 && window.location && window.location.reload) window.location.reload(); if(!response.ok) throw new Error(data.message||'Ошибка'); return data;
```

Нет `return` после `reload()`; загрузка страницы асинхронна, поэтому код успевает кинуть
`Error(data.message)` — сообщением пользователю станет «Для доступа к панели необходимо войти»
(это ок), но при ответе не-JSON (например, HTML login-страницы с кодом 200 от прокси)
`response.json()` кинет `SyntaxError` и сообщение будет техническим. Плюс при истёкшей сессии и
работающем `setInterval` (5 с) это даёт цикл «reload → 401 → reload».

---

## 40. 🟡 `index.html`: мёртвый параметр `remove` в `save()`

`index.html:700` (`async function save(remove,feedback='message')`), `index.html:704`
(`deleteKey:remove`), но все вызовы — `save(false, ...)` (`index.html:858,869,870,872,873`).
`save(true, …)` не вызывается нигде. Это «спящая» кнопка «удалить все ключи» (см. п.2): любой
будущий/внешний вызов с `true` сотрёт весь список.

---

## 41. 🟡 `AssistantButtonPolicy`: `repeat != 0` уходит в систему как новое нажатие

`AssistantButtonPolicy.java:11-20`: при долгом удержании `ACTION_DOWN` c `repeatCount > 0` и
`pressed == true` вернётся `CONSUME` только для первого повтора? Нет — `if (pressed) return CONSUME`
идёт **до** проверки `repeat`, поэтому повторы потребляются. Корректно. А вот если `pressed` стал
`false` из-за промежуточного `UP`, а затем пришёл `DOWN` с `repeat != 0` — `PASS` отдаст событие
системе. Редко. Оставляю как наблюдение (не подтверждённый баг).

---

## 42. 🟡 `PluginRegistry.log`/`list` под одним глобальным `synchronized` + чтение файлов

`PluginRegistry.java:24,120,132,145,155`: почти все методы `static synchronized`, включая `list()`
и `log()`, которые читают файлы плагинов с диска. `log()` вызывается из Binder-callback
`PluginExecution` (поток Binder) и из `PluginExecution.execute` — конкуренция с `list()` из
`/api/plugins` (каждые 5 с из панели) может подвесить вызовы. Мелочь производительности, но
на слабой станции заметно.

---

## Что проверил и багом НЕ считаю (чтобы не тратить время)

- `get_volume` **объявлен** в `DeviceToolSpec.declarations()` (`DeviceToolSpec.java:36`) и
  обрабатывается (`DeviceToolSpec.java:134`, `DeviceActions.java:61-64`) — моя первая гипотеза была
  неверной.
- `CueGate`, `PendingAudio`, `AudioFocusSession`, `DeadlineWait`, `TranscriptAccumulator`,
  `KeyRotation`, `KeyListCodec`, `GifValidation`, `LedProtocol`, `HelperProtocol` — логика
  соответствует тестам, ограничения размеров на месте.
- `LedPreviewAsset` (+тест), `PanelAccessPolicy`, `MaskLinkHints`, `YandexUrlPolicy`,
  `LinkPolicy`, `UserLinkAuthorization` — выглядят корректно.
- `node tools/test_voice_settings.js`, `test_home_rooms.js`, `test_led_editor.js`,
  `test_plugin_ui.js` — проходят.
- `useGzipWhenAccepted=false` и явные заголовки безопасности в `reply()` — ок.
- JVM-тесты и Gradle-сборку в песочнице не запускал (нет Android SDK/доступа к Maven), поэтому
  компиляционные/тестовые проблемы не проверены.

---

## Приоритет исправления (если будете брать)

1. п.1 (`?v=` превью) и п.2 (`deleteKey` → clear) — ломают UX и данные, правки маленькие.
2. п.3 (`apply()` vs `commit()` в `SecretStore.clear`) и п.4 (сетевой вызов под глобальным локом).
3. п.5, п.6, п.7 (Gemini Live таймеры/буфер/retry) — самое дорогое для пользователя.
4. п.8 (`AudioTrack.write == 0`), п.13 (`::ffff:127.0.0.1`) — «LED/TTS не работает» без причины.
5. Остальное — по вкусу.
