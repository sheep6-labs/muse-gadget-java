/*
 * Derived from the Muse Gadgets SDK (https://github.com/facebookincubator/muse-gadget-sdk),
 * Copyright (c) Meta Platforms, Inc. and affiliates, licensed under the Apache License, Version 2.0.
 * This file re-implements the SDK's /link-control link behaviour in Java (not a verbatim copy); see NOTICE.
 */
package dev.musegadget.core.link;

import dev.musegadget.core.json.Json;

import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * /link-control framing: JSON messages concatenated with a u32 little-endian length prefix;
 * a zero-length message is a keepalive and is dropped on receive. Matches SDK link_client.py
 * {@code encode_message} / {@code MessageDecoder}.
 */
public final class LinkFraming {
    public static final int MAX_INBOUND_MESSAGE = 4 * 1024 * 1024;
    /** VM's per-message limit, length prefix included (SDK MAX_CONTROL_MESSAGE_BYTES). */
    public static final int MAX_OUTBOUND_MESSAGE = 256 * 1024;
    /** Largest single slice accepted by {@link Decoder#feed} (bytes); may hold several messages. Larger is fatal. */
    public static final int MAX_FEED_FRAGMENT = 16 * 1024 * 1024;
    private static final Charset UTF8 = Charset.forName("UTF-8");

    private LinkFraming() {}

    /** Length-prefixed frame for one JSON text. @throws IllegalArgumentException if it exceeds the VM's 256 KiB budget */
    public static byte[] encode(String json) {
        byte[] body = json.getBytes(UTF8);
        byte[] out = new byte[4 + body.length];
        out[0] = (byte) body.length;
        out[1] = (byte) (body.length >>> 8);
        out[2] = (byte) (body.length >>> 16);
        out[3] = (byte) (body.length >>> 24);
        System.arraycopy(body, 0, out, 4, body.length);
        if (out.length > MAX_OUTBOUND_MESSAGE) throw new IllegalArgumentException("control message too large: " + out.length);
        return out;
    }

    public static byte[] encode(Map<String, Object> message) { return encode(Json.write(message)); }

    /** A keepalive frame (length 0). */
    public static byte[] keepalive() { return new byte[4]; }

    /**
     * Incremental decoder: feed arbitrary byte slices, get back the complete JSON objects. Keepalives are
     * dropped; malformed (bad UTF-8 / bad JSON) or non-object messages are dropped too, like the SDK.
     * An over-limit length prefix or an over-sized fragment ({@link #MAX_FEED_FRAGMENT}) is fatal ({@link IllegalStateException}).
     */
    public static final class Decoder {
        // Accumulation buffer: unread bytes are buf[start, end). Compacted or grown only when a feed does not fit,
        // so feeding a large message in small slices is linear overall rather than copying everything per feed.
        private byte[] buf = new byte[1024];
        private int start, end;

        public List<Map<String, Object>> feed(byte[] data) {
            if (data.length > MAX_FEED_FRAGMENT) throw new IllegalStateException("link-control fragment too large: " + data.length);
            if (end + data.length > buf.length) {
                if (start > 0) {
                    System.arraycopy(buf, start, buf, 0, end - start);
                    end -= start;
                    start = 0;
                }
                if (end + data.length > buf.length) {
                    buf = Arrays.copyOf(buf, Math.max(buf.length * 2, end + data.length));
                }
            }
            System.arraycopy(data, 0, buf, end, data.length);
            end += data.length;

            List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
            while (end - start >= 4) {
                long len = (buf[start] & 0xFFL) | ((buf[start + 1] & 0xFFL) << 8) | ((buf[start + 2] & 0xFFL) << 16) | ((buf[start + 3] & 0xFFL) << 24);
                if (len > MAX_INBOUND_MESSAGE) throw new IllegalStateException("link-control message too large: " + len);
                if (len == 0) { start += 4; continue; }
                if (end - start - 4 < len) break;
                try {
                    String text = UTF8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT)
                            .decode(ByteBuffer.wrap(buf, start + 4, (int) len)).toString();
                    out.add(Json.parseObject(text));
                } catch (CharacterCodingException e) {
                    // dropped
                } catch (Json.JsonException e) {
                    // dropped
                }
                start += 4 + (int) len;
            }
            if (start == end) {
                start = end = 0;
                if (buf.length > 64 * 1024) buf = new byte[1024]; // do not keep a large message's buffer alive
            }
            return out;
        }
    }
}
