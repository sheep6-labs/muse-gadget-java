package dev.musegadget.core.text;

import java.util.List;

/**
 * Sentence splitting for speech (pure Java, no platform dependency). Terminators stay attached to
 * their sentence; blank pieces are dropped. For text that arrives piece by piece use {@link SentenceStream}.
 */
public final class Sentences {
    /** Closing marks that stay with the sentence they follow, e.g. "！？" or "。」". */
    static final String CLOSERS = "！？!?。」』）)";

    private Sentences() {}

    static boolean isTerminator(char c) { return c == '。' || c == '！' || c == '？' || c == '!' || c == '?'; }

    static boolean isCloser(char c) { return CLOSERS.indexOf(c) >= 0; }

    /** Splits a complete text (no length cap: only terminators and line breaks end a sentence). */
    public static List<String> split(String text) {
        SentenceStream s = new SentenceStream(0);
        List<String> out = s.feed(text);
        out.addAll(s.flush());
        return out;
    }
}
