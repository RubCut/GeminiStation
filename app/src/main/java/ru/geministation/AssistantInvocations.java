package ru.geministation;

/** Common Android assistant entry points; vendor firmware may use its own route. */
final class AssistantInvocations {
    static final String ASSIST = "android.intent.action.ASSIST";
    static final String VOICE_COMMAND = "android.intent.action.VOICE_COMMAND";
    static final String WEB_SEARCH = "android.speech.action.WEB_SEARCH";
    static boolean isInvocation(String action) {
        return ASSIST.equals(action) || VOICE_COMMAND.equals(action) || WEB_SEARCH.equals(action);
    }
    private AssistantInvocations() { }
}
