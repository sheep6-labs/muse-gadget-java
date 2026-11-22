package dev.musegadget.core.text;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Sentence splitting for speech (pure Java, no platform dependency). Terminators stay attached to
 * their sentence; blank pieces are dropped. For text that arrives piece by piece use {@link SentenceStream}.
 */
public final class Sentences {
    /** Closing marks that stay with the sentence they follow, e.g. "！？" or "。」". */
    static final String CLOSERS = "！？!?。」』）)";

    /** Extra closing marks that may follow an English full stop ({@code He said "Go."}). */
    private static final String PERIOD_CLOSERS = "\"'\u201D\u2019";
    /** Opening marks that can start the next English sentence. */
    private static final String OPENERS = "\"'\u201C\u2018([{\u00BF\u00A1";
    /** Abbreviations after which a full stop never ends a sentence (titles, "vs.", "e.g.", ...). */
    private static final Set<String> JOINING = new HashSet<String>(Arrays.asList(
            "dr", "mr", "mrs", "ms", "st", "prof", "jr", "sr", "vs", "e.g", "i.e", "cf", "fig"));
    /** Abbreviations that end a sentence only when an uppercase letter follows ("etc. and more" stays joined). */
    private static final Set<String> JOINING_BEFORE_LOWER = new HashSet<String>(Arrays.asList("etc"));

    /** The full-stop decision "need more input": the buffer ends before the question can be answered. */
    static final int PERIOD_WAIT = -2;
    /** The full stop is not a sentence end. */
    static final int PERIOD_NONE = -1;

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

    /**
     * Decides whether the '.' at {@code i} ends an English sentence. Returns the exclusive end index of the sentence
     * (the full stop plus any closing marks), {@link #PERIOD_NONE} if it is not a sentence end, or {@link #PERIOD_WAIT}
     * if the text so far cannot tell yet (the buffer ends right after it). Conservative: when in doubt, do not split.
     */
    static int periodEnd(CharSequence t, int i) {
        int n = t.length();
        if (i + 1 < n && t.charAt(i + 1) == '.') return PERIOD_NONE; // inside "..."
        if (i > 0 && t.charAt(i - 1) == '.') return PERIOD_NONE;     // last dot of "..."
        int j = i + 1;
        while (j < n && (isCloser(t.charAt(j)) || PERIOD_CLOSERS.indexOf(t.charAt(j)) >= 0)) j++;
        if (j == n) return PERIOD_WAIT;
        char w = t.charAt(j);
        if (w == '\n' || w == '\r') return j;
        if (!Character.isWhitespace(w)) return PERIOD_NONE; // "3.14", "example.com"
        int k = j;
        while (k < n && Character.isWhitespace(t.charAt(k)) && t.charAt(k) != '\n' && t.charAt(k) != '\r') k++;
        if (k == n) return PERIOD_WAIT;
        char next = t.charAt(k);
        if (next != '\n' && next != '\r' && !startsSentence(next)) return PERIOD_NONE;
        String word = wordBefore(t, i);
        if (JOINING.contains(word.toLowerCase())) return PERIOD_NONE;
        if (JOINING_BEFORE_LOWER.contains(word.toLowerCase()) && !Character.isUpperCase(next)) return PERIOD_NONE;
        if (word.length() == 1 && Character.isUpperCase(word.charAt(0)) && word.charAt(0) != 'I') return PERIOD_NONE; // initials
        return j;
    }

    private static boolean startsSentence(char c) {
        return Character.isDigit(c) || OPENERS.indexOf(c) >= 0 || (Character.isLetter(c) && !Character.isLowerCase(c));
    }

    /** The run of letters and dots right before index {@code i} ("e.g" for "use e.g."), without leading dots. */
    private static String wordBefore(CharSequence t, int i) {
        int s = i;
        while (s > 0 && (Character.isLetter(t.charAt(s - 1)) || t.charAt(s - 1) == '.')) s--;
        while (s < i && t.charAt(s) == '.') s++;
        return t.subSequence(s, i).toString();
    }
}
