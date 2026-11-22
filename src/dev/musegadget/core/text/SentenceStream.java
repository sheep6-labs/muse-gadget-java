package dev.musegadget.core.text;

import java.util.ArrayList;
import java.util.List;

/**
 * Incremental sentence splitter: feed streamed text deltas, get each sentence as soon as its end is certain.
 * A sentence ends at a terminator (。！？!?) plus any closing marks after it, or at a line break. A terminator at the
 * very end of the buffer is not final yet (a closing mark such as 」 may still follow in the next delta), so it waits
 * for the next character or for {@link #flush()}. With a soft limit, an over-long run without a terminator is cut at
 * its last comma so the first words are not held back by one huge sentence. Independently of that, a run reaching
 * {@link #HARD_LIMIT} chars is force-flushed, so the buffer cannot grow without bound. Not thread-safe.
 */
public final class SentenceStream {
    /** Hard cap on an unfinished run (chars): reaching it forces a flush even without a terminator or comma. */
    public static final int HARD_LIMIT = 2048;
    private final StringBuilder pending = new StringBuilder();
    private final int softLimit;

    /** @param softLimit cut a terminator-less run at its last comma once it reaches this many chars; 0 = never */
    public SentenceStream(int softLimit) { this.softLimit = softLimit; }

    public List<String> feed(String delta) {
        List<String> out = new ArrayList<String>();
        if (delta == null || delta.isEmpty()) return out;
        pending.append(delta);
        int n = pending.length(), start = 0, i = 0;
        while (i < n) {
            char c = pending.charAt(i);
            if (c == '\n' || c == '\r') {
                emit(start, i, out);
                start = ++i;
            } else if (Sentences.isTerminator(c)) {
                int j = i + 1;
                while (j < n && Sentences.isCloser(pending.charAt(j))) j++;
                if (j == n) break; // closers may continue in the next delta
                emit(start, j, out);
                start = i = j;
            } else {
                i++;
            }
        }
        pending.delete(0, start);
        while (softLimit > 0 && pending.length() >= softLimit) {
            int cut = lastComma(pending);
            if (cut <= 0) break;
            emit(0, cut + 1, out);
            pending.delete(0, cut + 1);
        }
        while (pending.length() >= HARD_LIMIT) {
            int cut = HARD_LIMIT;
            if (Character.isHighSurrogate(pending.charAt(cut - 1))) cut--; // never split a surrogate pair
            emit(0, cut, out);
            pending.delete(0, cut);
        }
        return out;
    }

    /** The unfinished tail as a final sentence (end of message). */
    public List<String> flush() {
        List<String> out = new ArrayList<String>();
        emit(0, pending.length(), out);
        pending.setLength(0);
        return out;
    }

    private static int lastComma(CharSequence s) {
        for (int k = s.length() - 1; k >= 0; k--) {
            char c = s.charAt(k);
            if (c == '、' || c == '，' || c == ',') return k;
        }
        return -1;
    }

    private void emit(int from, int to, List<String> out) {
        String s = pending.substring(from, to).trim();
        if (!s.isEmpty()) out.add(s);
    }
}
