package ru.geministation;

import org.json.JSONArray;
import org.json.JSONObject;

/** Strict allowlist for Gemini function calls; model output never becomes an Intent or shell command. */
final class DeviceToolSpec {
    enum Kind { OPEN_APP, OPEN_LINK, SEARCH_YOUTUBE, OPEN_SETTINGS, GO_HOME, SET_VOLUME, ADJUST_VOLUME, GET_VOLUME, MEDIA_CONTROL }
    static final class Action {
        final Kind kind;
        final int number;
        final String data;
        Action(Kind kind, int number) { this(kind, number, null); }
        Action(Kind kind, int number, String data) { this.kind = kind; this.number = number; this.data = data; }
    }
    static JSONArray declarations() throws Exception {
        JSONArray declarations = new JSONArray();
        declarations.put(function("open_app", "Открыть доступное приложение по его числовому id из списка приложений.",
                new JSONObject().put("id", new JSONObject().put("type", "INTEGER").put("description", "id приложения из текущего списка")), "id"));
        declarations.put(new JSONObject().put("name", "open_link").put("description", "Открыть указанную пользователем HTTP или HTTPS ссылку в приложении с данным id, если оно поддерживает ссылку. Не придумывай адреса.")
                .put("parameters", new JSONObject().put("type", "OBJECT")
                        .put("properties", new JSONObject().put("id", new JSONObject().put("type", "INTEGER"))
                                .put("url", new JSONObject().put("type", "STRING")))
                        .put("required", new JSONArray().put("id").put("url"))));
        declarations.put(new JSONObject().put("name", "search_youtube").put("description", "Найти видео YouTube по запросу и открыть через совместимое приложение id из списка. Не придумывай адрес видео.")
                .put("parameters", new JSONObject().put("type", "OBJECT")
                        .put("properties", new JSONObject().put("id", new JSONObject().put("type", "INTEGER"))
                                .put("query", new JSONObject().put("type", "STRING")))
                        .put("required", new JSONArray().put("id").put("query"))));
        declarations.put(function("open_settings", "Открыть основные настройки устройства.", new JSONObject(), null));
        declarations.put(function("go_home", "Вернуться на рабочий стол устройства.", new JSONObject(), null));
        declarations.put(function("set_volume", "Установить громкость мультимедиа в процентах от 0 до 100; уровень станции 3 из 10 означает 30 процентов, а 6,5 — 65 процентов.",
                new JSONObject().put("percent", new JSONObject().put("type", "INTEGER").put("description", "целое число от 0 до 100")), "percent"));
        declarations.put(function("adjust_volume", "Сделать громкость мультимедиа немного выше или ниже.",
                new JSONObject().put("direction", new JSONObject().put("type", "STRING").put("enum", new JSONArray().put("up").put("down"))), "direction"));
        declarations.put(function("get_volume", "Узнать текущую громкость мультимедиа.", new JSONObject(), null));
        declarations.put(function("media_control", "Отправить команду текущему Android-медиаплееру. Доступны play (продолжить), pause, stop, next, previous, fast_forward и rewind. Не используй для запуска конкретной песни, узнавания текущего трека или перемотки на точное время. Android выбирает плеер, выполнение не подтверждается.",
                new JSONObject().put("action", new JSONObject().put("type", "STRING")
                        .put("enum", new JSONArray().put("play").put("pause").put("stop").put("next").put("previous").put("fast_forward").put("rewind"))), "action"));
        return declarations;
    }
    private static JSONObject function(String name, String description, JSONObject properties, String required) throws Exception {
        JSONObject result = new JSONObject().put("name", name).put("description", description);
        if (required != null) result.put("parameters", new JSONObject().put("type", "OBJECT")
                .put("properties", properties).put("required", new JSONArray().put(required)));
        return result;
    }
    private static Integer integer(JSONObject args, String name) {
        Object raw = args.opt(name);
        if (!(raw instanceof Number)) return null;
        double value = ((Number) raw).doubleValue();
        if (!Double.isFinite(value) || value != Math.rint(value) || value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) return null;
        return (int) value;
    }
    static Action parse(JSONObject call, int appCount) {
        if (call == null || !(call.opt("name") instanceof String)) return null;
        JSONObject args = call.optJSONObject("args");
        if (args == null) args = new JSONObject();
        switch (call.optString("name", "")) {
            case "open_app": {
                Integer id = integer(args, "id");
                return id != null && id >= 0 && id < appCount ? new Action(Kind.OPEN_APP, id) : null;
            }
            case "open_link":
            case "search_youtube": {
                Integer id = integer(args, "id");
                if (id == null || id < 0 || id >= appCount) return null;
                boolean search = "search_youtube".equals(call.optString("name", ""));
                Object data = args.opt(search ? "query" : "url");
                if (!(data instanceof String)) return null;
                String value = (String) data;
                if (search ? !LinkPolicy.searchQuery(value) : !LinkPolicy.allowed(value)) return null;
                return new Action(search ? Kind.SEARCH_YOUTUBE : Kind.OPEN_LINK, id, value);
            }
            case "open_settings": return new Action(Kind.OPEN_SETTINGS, 0);
            case "go_home": return new Action(Kind.GO_HOME, 0);
            case "set_volume": {
                Integer percent = integer(args, "percent");
                return percent != null && percent >= 0 && percent <= 100 ? new Action(Kind.SET_VOLUME, percent) : null;
            }
            case "adjust_volume": {
                Object direction = args.opt("direction");
                if ("up".equals(direction)) return new Action(Kind.ADJUST_VOLUME, 1);
                if ("down".equals(direction)) return new Action(Kind.ADJUST_VOLUME, -1);
                return null;
            }
            case "media_control": {
                Object requested = args.opt("action");
                if (!(requested instanceof String) || MediaCommands.parse((String) requested) == null) return null;
                return new Action(Kind.MEDIA_CONTROL, 0, (String) requested);
            }
            case "get_volume": return new Action(Kind.GET_VOLUME, 0);
            default: return null;
        }
    }
    static boolean eligible(boolean launcher, boolean appEnabled, boolean activityEnabled,
                            boolean exported, boolean permissionGranted, boolean ownPackage) {
        return launcher && appEnabled && activityEnabled && exported && permissionGranted && !ownPackage;
    }
    private DeviceToolSpec() { }
}
