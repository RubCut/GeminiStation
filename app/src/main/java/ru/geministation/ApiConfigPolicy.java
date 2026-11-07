package ru.geministation;

import org.json.JSONObject;

/** The old boolean deletion action is unsafe with multiple independent API keys. */
final class ApiConfigPolicy {
    static boolean acceptsLegacyDeleteFlag(JSONObject data) {
        return !data.has("deleteKey") || Boolean.FALSE.equals(data.opt("deleteKey"));
    }
    private ApiConfigPolicy() { }
}
