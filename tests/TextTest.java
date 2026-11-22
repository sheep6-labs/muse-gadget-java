import dev.musegadget.core.text.SentenceStream;
import dev.musegadget.core.text.Sentences;

import java.util.ArrayList;
import java.util.List;

/** Sentence splitting: whole text and streamed deltas. */
public final class TextTest {
    static String j(List<String> l) { return String.join("|", l); }

    /** Feeds {@code deltas} one by one; each element of the result is what that delta released ("" if nothing). */
    static List<String> stream(int soft, String... deltas) {
        SentenceStream s = new SentenceStream(soft);
        List<String> out = new ArrayList<String>();
        for (String d : deltas) out.add(j(s.feed(d)));
        out.add(j(s.flush()));
        return out;
    }

    public static void main(String[] args) {
        // --- whole text
        T.eq("split: 。", "こんにちは。|元気？|はい", j(Sentences.split("こんにちは。元気？はい")));
        T.eq("split: !? runs and closing quote stay", "えっ！？|「ほんと。」|うん", j(Sentences.split("えっ！？「ほんと。」うん")));
        T.eq("split: newlines, blanks dropped", "a|b", j(Sentences.split("a\n\n  \r\nb")));
        T.check("split: blank -> empty", Sentences.split(" \n ").isEmpty());
        T.eq("split: ASCII terminators", "Hi!|OK?", j(Sentences.split("Hi! OK?")));

        // --- streamed: a sentence is released as soon as its end is certain
        T.eq("stream: terminator at buffer end waits; the next char releases it", "[, , こんにちは。, げん]", String.valueOf(stream(0, "こんに", "ちは。", "げん")));
        T.eq("stream: terminator inside a delta releases at once", "[, こんにちは。, , , げんき？]", String.valueOf(stream(0, "こんに", "ちは。げ", "んき", "？")));
        T.eq("stream: closer in the next delta stays with the sentence", "[, , 「ほんと。」]", String.valueOf(stream(0, "「ほんと。", "」")));
        T.eq("stream: closer then next sentence", "[, 「ほんと。」, う]", String.valueOf(stream(0, "「ほんと。」", "う")));
        T.eq("stream: flush releases an unfinished tail", "[, まだ途中]", String.valueOf(stream(0, "まだ途中")));
        T.eq("stream: newline releases at once", "[a, b]", String.valueOf(stream(0, "a\nb")));
        T.eq("stream: !? run split across deltas stays one sentence", "[, , えっ！？]", String.valueOf(stream(0, "えっ！", "？")));
        T.eq("stream: deltas == whole-text split", j(Sentences.split("今日は晴れ。明日は雨？「はい。」終わり")),
                j(joinAll(stream(0, "今", "日は晴れ", "。明日は", "雨？「", "はい。」", "終わ", "り"))));
        // --- English full stops
        T.eq("en: . ? !", "Hello world.|How are you?|Fine!", j(Sentences.split("Hello world. How are you? Fine!")));
        T.eq("en: decimal does not split", "It costs 3.14 dollars.|OK.", j(Sentences.split("It costs 3.14 dollars. OK.")));
        T.eq("en: title abbreviation joins", "Dr. Smith went home.|He slept.", j(Sentences.split("Dr. Smith went home. He slept.")));
        T.eq("en: e.g. joins", "Use e.g. this one.|Done.", j(Sentences.split("Use e.g. this one. Done.")));
        T.eq("en: domain does not split", "Visit example.com.|Then rest.", j(Sentences.split("Visit example.com. Then rest.")));
        T.eq("en: ellipsis does not split", "Well... maybe so.|Yes.", j(Sentences.split("Well... maybe so. Yes.")));
        T.eq("en: etc. before lowercase joins, before uppercase splits", "Pens, etc. are here.|Pens, etc.|Then more.", j(Sentences.split("Pens, etc. are here. Pens, etc. Then more.")));
        T.eq("en: closing quote stays with the sentence", "He said \"Go.\"|Then left.", j(Sentences.split("He said \"Go.\" Then left.")));
        T.eq("en: opening quote / digit starts a sentence", "Stop.|\"Go,\" he said.|2 more.", j(Sentences.split("Stop. \"Go,\" he said. 2 more.")));
        T.eq("en: lowercase after a full stop does not split", "see fig. 2 for details.", j(Sentences.split("see fig. 2 for details.")));
        T.eq("en: pronoun I at the end splits, initials join", "So do I.|J. Smith too.", j(Sentences.split("So do I. J. Smith too.")));
        T.eq("en: mixed with Japanese", "今日は晴れです。|Tomorrow too.|そうですね。", j(Sentences.split("今日は晴れです。Tomorrow too. そうですね。")));
        T.eq("en: stream trailing full stop waits for the next delta", "[, Hello., World.]", String.valueOf(stream(0, "Hello.", " World.")));
        T.eq("en: stream decimal split across deltas", "[, , It is 3.14 kg.]", String.valueOf(stream(0, "It is 3.", "14 kg.")));
        T.eq("en: stream release once the next sentence starts", "[Hello., , World.]", String.valueOf(stream(0, "Hello. W", "orld.")));

        // --- soft limit: cut a terminator-less run at its last comma
        String longRun = "あいうえお、かきくけこ、さしすせそ";
        T.eq("soft limit cuts at last comma", "あいうえお、かきくけこ、|さしすせそ", j(joinAll(stream(10, longRun))));
        T.eq("soft limit without a comma waits", "あいうえおかきくけこ", j(joinAll(stream(5, "あいうえおかきくけこ"))));
        T.eq("soft limit off", longRun, j(joinAll(stream(0, longRun))));

        // --- hard limit: a run without terminator or comma is force-flushed
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 5000; i++) big.append('あ');
        SentenceStream hs = new SentenceStream(0);
        List<String> hr = hs.feed(big.toString());
        T.check("hard limit forces flush of a terminator-less run", hr.size() == 2 && hr.get(0).length() == SentenceStream.HARD_LIMIT && hr.get(1).length() == SentenceStream.HARD_LIMIT, String.valueOf(hr.size()));
        T.eq("hard limit keeps the tail for flush", String.valueOf(5000 - 2 * SentenceStream.HARD_LIMIT), String.valueOf(hs.flush().get(0).length()));
        SentenceStream sp = new SentenceStream(0);
        StringBuilder emoji = new StringBuilder("a");
        for (int i = 0; i < SentenceStream.HARD_LIMIT; i++) emoji.append("\uD83D\uDE00"); // surrogate pairs straddle the cut
        boolean pairsIntact = true;
        for (String s : sp.feed(emoji.toString())) pairsIntact &= !Character.isHighSurrogate(s.charAt(s.length() - 1));
        T.check("hard limit never splits a surrogate pair", pairsIntact);
        T.check("hard limit does not disturb normal splitting", j(joinAll(stream(0, "こんにちは。元気？"))).equals("こんにちは。元気？".replace("。", "。|")));

        T.done();
    }

    static List<String> joinAll(List<String> perDelta) {
        List<String> all = new ArrayList<String>();
        for (String s : perDelta) if (!s.isEmpty()) for (String x : s.split("\\|")) all.add(x);
        return all;
    }
}
