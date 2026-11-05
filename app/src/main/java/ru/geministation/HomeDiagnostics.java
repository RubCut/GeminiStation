package ru.geministation;

import org.json.JSONArray;
import org.json.JSONObject;

/** Emit only known protocol enums; never log provider messages, IDs, names or response bodies. */
final class HomeDiagnostics {
    static String errorCode(String value) {
        if (value == null) return "без кода";
        switch (value) {
            case "DOOR_OPEN": case "LID_OPEN": case "REMOTE_CONTROL_DISABLED": case "NOT_ENOUGH_WATER":
            case "LOW_CHARGE_LEVEL": case "CONTAINER_FULL": case "CONTAINER_EMPTY": case "DRIP_TRAY_FULL":
            case "DEVICE_STUCK": case "DEVICE_OFF": case "FIRMWARE_OUT_OF_DATE": case "NOT_ENOUGH_DETERGENT":
            case "HUMAN_INVOLVEMENT_NEEDED": case "DEVICE_UNREACHABLE": case "DEVICE_BUSY": case "INTERNAL_ERROR":
            case "INVALID_ACTION": case "INVALID_VALUE": case "NOT_SUPPORTED_IN_CURRENT_MODE":
            case "ACCOUNT_LINKING_ERROR": case "DEVICE_NOT_FOUND": case "ACCESS_DENIED":
                return value;
            default: return value.isEmpty() ? "без кода" : "неизвестный код";
        }
    }
    static String outcome(JSONObject response, String deviceId) {
        return outcome(response, deviceId, "devices.capabilities.on_off", "on");
    }
    static String outcome(JSONObject response, String deviceId, String type, String instance) {
        if (!"ok".equals(response.optString("status"))) return "общая ошибка ответа";
        JSONArray devices = response.optJSONArray("devices");
        if (devices == null) return "нет списка результатов";
        for (int i = 0; i < devices.length(); i++) {
            JSONObject d = devices.optJSONObject(i);
            if (d == null || !deviceId.equals(d.optString("id"))) continue;
            JSONArray caps = d.optJSONArray("capabilities");
            if (caps == null) return "нет результатов функций";
            for (int j = 0; j < caps.length(); j++) {
                JSONObject cap = caps.optJSONObject(j);
                if (cap == null || !type.equals(cap.optString("type"))) continue;
                JSONObject state = cap.optJSONObject("state");
                if (state == null || !instance.equals(state.optString("instance"))) continue;
                JSONObject result = state.optJSONObject("action_result");
                if (result == null) return "нет результата действия";
                String status = result.optString("status", "");
                if ("DONE".equals(status)) return "DONE";
                if ("ERROR".equals(status)) return "ERROR: " + errorCode(result.optString("error_code", ""));
                return "неизвестный статус действия";
            }
            return "нет результата функции";
        }
        return "нет результата выбранного устройства";
    }
    private HomeDiagnostics() { }
}
