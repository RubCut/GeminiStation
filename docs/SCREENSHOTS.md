# Скриншоты веб-панели

[← Вернуться к README](../README.md)

Снимки показывают текущий интерфейс на компьютере (1365 × 900) и телефоне (390 × 844). На них видимая область вкладки; остальное доступно прокруткой.

**Это демонстрация с тестовыми данными**, а не снимки реальной станции: состояние подключения, пример плагина и записи журнала имитированы. Ключей, токенов и личных данных на снимках нет.

[Компьютер](#компьютер) · [Телефон](#телефон)

## Компьютер

### Обзор

<img src="screenshots/desktop-summary.jpg" alt="Обзор — веб-панель на компьютере" width="100%">

### Ассистент

<img src="screenshots/desktop-assistant.jpg" alt="Ассистент — веб-панель на компьютере" width="100%">

### Голос и активация

<img src="screenshots/desktop-activation.jpg" alt="Голос и активация — веб-панель на компьютере" width="100%">

### Инструкции

<img src="screenshots/desktop-editor.jpg" alt="Инструкции — веб-панель на компьютере" width="100%">

### Экран станции

<img src="screenshots/desktop-display.jpg" alt="Экран станции — веб-панель на компьютере" width="100%">

### Умный дом и музыка

<img src="screenshots/desktop-smartHome.jpg" alt="Умный дом и музыка — веб-панель на компьютере" width="100%">

### Плагины

<img src="screenshots/desktop-plugins.jpg" alt="Плагины — веб-панель на компьютере" width="100%">

Настройки и журнал примера плагина:

<img src="screenshots/desktop-plugins-details.jpg" alt="Настройки и журнал плагина — компьютер" width="100%">

### Журнал

<img src="screenshots/desktop-journal.jpg" alt="Журнал — веб-панель на компьютере" width="100%">

## Телефон

### Обзор

<img src="screenshots/mobile-summary.jpg" alt="Обзор — веб-панель на телефоне" width="390">

### Ассистент

<img src="screenshots/mobile-assistant.jpg" alt="Ассистент — веб-панель на телефоне" width="390">

### Голос и активация

<img src="screenshots/mobile-activation.jpg" alt="Голос и активация — веб-панель на телефоне" width="390">

### Инструкции

<img src="screenshots/mobile-editor.jpg" alt="Инструкции — веб-панель на телефоне" width="390">

### Экран станции

<img src="screenshots/mobile-display.jpg" alt="Экран станции — веб-панель на телефоне" width="390">

### Умный дом и музыка

<img src="screenshots/mobile-smartHome.jpg" alt="Умный дом и музыка — веб-панель на телефоне" width="390">

### Плагины

<img src="screenshots/mobile-plugins.jpg" alt="Плагины — веб-панель на телефоне" width="390">

Настройки и журнал примера плагина:

<img src="screenshots/mobile-plugins-details.jpg" alt="Настройки и журнал плагина — телефон" width="390">

### Журнал

<img src="screenshots/mobile-journal.jpg" alt="Журнал — веб-панель на телефоне" width="390">

[↑ К началу](#скриншоты-веб-панели) · [README](../README.md)

## Обновить снимки

Скрипт [`tools/capture_panel_screenshots.js`](../tools/capture_panel_screenshots.js) открывает актуальный HTML панели с локальными тестовыми ответами; к станции и внешним сервисам он не подключается. Для запуска нужны Node.js, `playwright-core` и `@sparticuz/chromium`, например:

```sh
npm install --prefix /tmp/gemini-screenshots playwright-core@1.58.2 @sparticuz/chromium@143.0.0
NODE_PATH=/tmp/gemini-screenshots/node_modules node tools/capture_panel_screenshots.js
```

Сборке Chromium могут потребоваться системные библиотеки Linux. Проверьте готовые изображения перед публикацией и не подставляйте реальные ключи или журналы.
