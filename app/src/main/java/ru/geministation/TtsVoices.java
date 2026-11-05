package ru.geministation;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** The 30 prebuilt Gemini TTS voices; identifiers are case-sensitive. */
final class TtsVoices {
    static final String DEFAULT = "Kore";
    static final List<String> ALL = Collections.unmodifiableList(Arrays.asList(
            "Zephyr", "Puck", "Charon", "Kore", "Fenrir", "Leda", "Orus", "Aoede",
            "Callirrhoe", "Autonoe", "Enceladus", "Iapetus", "Umbriel", "Algieba",
            "Despina", "Erinome", "Algenib", "Rasalgethi", "Laomedeia", "Achernar",
            "Alnilam", "Schedar", "Gacrux", "Pulcherrima", "Achird", "Zubenelgenubi",
            "Vindemiatrix", "Sadachbia", "Sadaltager", "Sulafat"));
    static boolean valid(String voice) { return ALL.contains(voice); }
    static String orDefault(String voice) { return valid(voice) ? voice : DEFAULT; }
    private TtsVoices() { }
}
