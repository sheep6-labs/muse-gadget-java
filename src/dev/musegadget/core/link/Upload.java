/*
 * Derived from the Muse Gadgets SDK (https://github.com/facebookincubator/muse-gadget-sdk),
 * Copyright (c) Meta Platforms, Inc. and affiliates, licensed under the Apache License, Version 2.0.
 * This file re-implements the SDK's /link-control link behaviour in Java (not a verbatim copy); see NOTICE.
 */
package dev.musegadget.core.link;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Request-body size rules of the chat path (as in SDK link_client.py): bodies over 64 KiB are sent as
 * an open stream followed by 16 KiB body chunks; a WAV voice note is at most 2 MiB.
 */
public final class Upload {
    public static final int MAX_INLINE_REQUEST_BYTES = 64 * 1024;
    public static final int REQUEST_CHUNK_BYTES = 16 * 1024;
    public static final int MAX_VOICE_BYTES = 2 * 1024 * 1024;

    private Upload() {}

    public static boolean needsChunking(byte[] body) { return body.length > MAX_INLINE_REQUEST_BYTES; }

    /** The 16 KiB parts of {@code body} (the last one may be shorter). Empty body gives no parts. */
    public static List<byte[]> chunks(byte[] body) {
        List<byte[]> parts = new ArrayList<byte[]>();
        for (int off = 0; off < body.length; off += REQUEST_CHUNK_BYTES) {
            parts.add(Arrays.copyOfRange(body, off, Math.min(body.length, off + REQUEST_CHUNK_BYTES)));
        }
        return parts;
    }

    /** @throws IllegalArgumentException if empty or over the 2 MiB WAV limit */
    public static void checkVoice(byte[] wav) {
        if (wav == null || wav.length == 0 || wav.length > MAX_VOICE_BYTES) {
            throw new IllegalArgumentException("voice note must contain 1.." + MAX_VOICE_BYTES + " bytes of WAV audio");
        }
    }
}
