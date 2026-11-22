import dev.musegadget.core.json.Json;
import dev.musegadget.core.link.LinkFraming;

import java.io.ByteArrayOutputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** link-control framing (u32-LE length + JSON, 0-length keepalive) and the minimal JSON codec. */
public final class LinkFramingTest {
    static Map<String, Object> msg(String type, long n) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("type", type);
        m.put("n", n);
        m.put("text", "日本語\"q\"\n\u0001 \uD83D\uDE00");
        return m;
    }

    static byte[] cat(byte[]... parts) throws Exception {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (byte[] p : parts) o.write(p);
        return o.toByteArray();
    }

    public static void main(String[] args) throws Exception {
        byte[] stream = cat(LinkFraming.encode(msg("a", 1)), LinkFraming.keepalive(), LinkFraming.encode(msg("b", 2)),
                LinkFraming.keepalive(), LinkFraming.keepalive(), LinkFraming.encode(msg("c", 3)));
        T.check("prefix is u32 little-endian", LinkFraming.encode("{}")[0] == 2 && LinkFraming.encode("{}")[1] == 0 && LinkFraming.encode("{}").length == 6);

        List<Map<String, Object>> all = new LinkFraming.Decoder().feed(stream);
        T.check("one feed: 3 messages, keepalives dropped", all.size() == 3, "n=" + all.size());
        T.check("order and content preserved", "a".equals(all.get(0).get("type")) && "b".equals(all.get(1).get("type"))
                && "c".equals(all.get(2).get("type")) && ((Number) all.get(2).get("n")).longValue() == 3
                && msg("x", 0).get("text").equals(all.get(0).get("text")));

        // byte-at-a-time and odd splits give the same result
        LinkFraming.Decoder d = new LinkFraming.Decoder();
        int count = 0;
        for (byte b : stream) count += d.feed(new byte[] {b}).size();
        T.check("byte-at-a-time feed: same 3 messages", count == 3, "n=" + count);
        d = new LinkFraming.Decoder();
        count = 0;
        for (int i = 0; i < stream.length; i += 7) {
            byte[] slice = new byte[Math.min(7, stream.length - i)];
            System.arraycopy(stream, i, slice, 0, slice.length);
            count += d.feed(slice).size();
        }
        T.check("7-byte slices: same 3 messages", count == 3, "n=" + count);

        // malformed JSON / non-object / bad UTF-8 are dropped, stream continues
        byte[] junk = cat(LinkFraming.encode("not json"), LinkFraming.encode("[1,2]"),
                new byte[] {2, 0, 0, 0, (byte) 0xFF, (byte) 0xFE}, LinkFraming.encode(msg("ok", 9)));
        List<Map<String, Object>> r = new LinkFraming.Decoder().feed(junk);
        T.check("malformed/non-object/bad-utf8 dropped, next one survives", r.size() == 1 && "ok".equals(r.get(0).get("type")));

        // limits
        boolean threw = false;
        try { new LinkFraming.Decoder().feed(new byte[] {0, 0, 0x40, 0x01}); } catch (IllegalStateException e) { threw = true; } // 0x01400000 > 4 MiB
        T.check("inbound length > 4 MiB is fatal", threw);
        StringBuilder big = new StringBuilder("\"");
        for (int i = 0; i < 256 * 1024; i++) big.append('x');
        threw = false;
        try { LinkFraming.encode(big.append('"').toString()); } catch (IllegalArgumentException e) { threw = true; }
        T.check("outbound > 256 KiB (with prefix) refused", threw);
        T.check("outbound exactly 256 KiB incl. prefix accepted", LinkFraming.encode(rep(256 * 1024 - 4)).length == 256 * 1024);

        // a large message fed one byte at a time stays linear (quadratic copying would take minutes here)
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 2 * 1024 * 1024; i++) text.append((char) ('a' + i % 26));
        Map<String, Object> bigMsg = new LinkedHashMap<String, Object>();
        bigMsg.put("text", text.toString());
        byte[] bigBody = Json.write(bigMsg).getBytes("UTF-8"); // over the 256 KiB outbound limit, so framed by hand
        byte[] bigPrefix = {(byte) bigBody.length, (byte) (bigBody.length >>> 8), (byte) (bigBody.length >>> 16), (byte) (bigBody.length >>> 24)};
        byte[] bigFrame = cat(LinkFraming.encode(msg("first", 1)), bigPrefix, bigBody, LinkFraming.encode(msg("last", 2)));
        d = new LinkFraming.Decoder();
        List<Map<String, Object>> got = new java.util.ArrayList<Map<String, Object>>();
        long t0 = System.nanoTime();
        for (byte b : bigFrame) got.addAll(d.feed(new byte[] {b}));
        long ms = (System.nanoTime() - t0) / 1000000;
        T.check("2 MiB message fed byte-by-byte decodes intact", got.size() == 3 && "first".equals(got.get(0).get("type"))
                && text.toString().equals(got.get(1).get("text")) && "last".equals(got.get(2).get("type")), "n=" + got.size());
        T.check("byte-by-byte feed of 2 MiB is fast", ms < 20000, ms + " ms");

        // JSON codec
        T.check("json rejects duplicate object keys", rejects("{\"a\":1,\"a\":2}") && rejects("{\"x\":{\"k\":1,\"k\":null}}"));
        T.check("json accepts same key in different objects", !rejects("{\"a\":{\"k\":1},\"b\":{\"k\":2}}"));
        List<Map<String, Object>> dup = new LinkFraming.Decoder().feed(cat(LinkFraming.encode("{\"t\":1,\"t\":2}"), LinkFraming.encode(msg("ok", 1))));
        T.check("link message with duplicate keys is dropped, stream continues", dup.size() == 1 && "ok".equals(dup.get(0).get("type")));
        Map<String, Object> o = Json.parseObject("{\"a\":[1,2.5,-3,true,false,null,\"\\u00e9\\n\"],\"b\":{\"c\":1e2}}");
        T.check("json parse", ((java.util.List<?>) o.get("a")).size() == 7 && "é\n".equals(((java.util.List<?>) o.get("a")).get(6)));
        T.check("json compact write round-trips", Json.write(Json.parse(Json.write(o))).equals(Json.write(o)));
        T.check("json write is compact", Json.write(msg("a", 1)).startsWith("{\"type\":\"a\",\"n\":1,"));
        String[] bad = {"", "{", "{\"a\":}", "[1,]", "{\"a\":1} x", "\"\\q\"", "{'a':1}", "01x", "\"\u0001\""};
        int rejected = 0;
        for (String s : bad) { try { Json.parse(s); } catch (Json.JsonException e) { rejected++; } }
        T.check("json rejects malformed input", rejected == bad.length, rejected + "/" + bad.length);
        boolean tooBig = false;
        try { new LinkFraming.Decoder().feed(new byte[LinkFraming.MAX_FEED_FRAGMENT + 1]); } catch (IllegalStateException e) { tooBig = true; }
        T.check("over-sized single fragment is fatal", tooBig);
        byte[] atMax = new byte[LinkFraming.MAX_FEED_FRAGMENT]; // zero bytes = keepalives, all dropped
        T.check("fragment of exactly the cap is accepted", new LinkFraming.Decoder().feed(atMax).isEmpty());
        T.check("4 MiB length prefix still accepted (waits for the body)", new LinkFraming.Decoder().feed(new byte[]{0, 0, 64, 0}).isEmpty());
        boolean prefixFatal = false;
        try { new LinkFraming.Decoder().feed(new byte[]{1, 0, 64, 0}); } catch (IllegalStateException e) { prefixFatal = true; }
        T.check("length prefix above 4 MiB is still fatal", prefixFatal);
        T.done();
    }

    static boolean rejects(String json) {
        try { Json.parse(json); return false; } catch (Json.JsonException e) { return true; }
    }

    static String rep(int n) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < n - 2; i++) sb.append('y');
        return sb.append('"').toString();
    }
}
