package ru.geministation;

import org.json.JSONObject;

/** Controls only the post-answer microphone lifecycle; absent/malformed directives continue safely. */
final class DialogDirective {
    static final String END = "[[DIALOG_END]]", CONTINUE = "[[DIALOG_CONTINUE]]";
    static final class Parsed {
        final String text;
        final boolean finish;
        Parsed(String text, boolean finish) { this.text = text; this.finish = finish; }
    }
    static Parsed text(String input) {
        String value = input == null ? "" : input.trim();
        boolean finish = false;
        if (value.endsWith(END)) { value = value.substring(0, value.length() - END.length()).trim(); finish = true; }
        else if (value.endsWith(CONTINUE)) value = value.substring(0, value.length() - CONTINUE.length()).trim();
        // Never leak protocol markers into TTS, even if the model duplicated one.
        value = value.replace(END, "").replace(CONTINUE, "").trim();
        return new Parsed(value.isEmpty() ? "Хорошо." : value, finish);
    }
    static boolean toolFinishes(JSONObject call) {
        JSONObject args = call == null ? null : call.optJSONObject("args");
        return args != null && "end".equals(args.optString("dialog", ""));
    }
    private DialogDirective() { }
}
