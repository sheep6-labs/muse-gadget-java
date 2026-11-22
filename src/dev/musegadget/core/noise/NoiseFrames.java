/*
 * Derived from the Muse Gadgets SDK (https://github.com/facebookincubator/muse-gadget-sdk),
 * Copyright (c) Meta Platforms, Inc. and affiliates, licensed under the Apache License, Version 2.0.
 * This file re-implements the SDK's Noise transport behaviour in Java (not a verbatim copy); see NOTICE.
 */
package dev.musegadget.core.noise;

import dev.musegadget.core.noise.Proto.In;
import dev.musegadget.core.noise.Proto.Out;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * NoiseTransportFrame chunking and reassembly, matching SDK noise/framing.py.
 * Frame fields: 1=chunk_id(int64) 2=chunk_index(uint32) 3=total_chunks(uint32) 4=payload(bytes).
 */
public final class NoiseFrames {
    public static final int MAX_CHUNK_PAYLOAD = 65489;
    public static final int MAX_PENDING_ASSEMBLIES = 16;
    public static final int MAX_TOTAL_CHUNKS = 256;
    public static final int MAX_ASSEMBLY_BYTES = 16 * 1024 * 1024;
    /**
     * Budget for all in-flight reassemblies together. One assembly may use up to 16 MiB and 16 may be pending,
     * which alone would allow 256 MiB; this caps the total at two maximum-size messages (32 MiB), enough for
     * overlapping large transfers while bounding memory on small devices.
     */
    public static final int MAX_PENDING_BYTES = 32 * 1024 * 1024;
    public static final long ASSEMBLY_TTL_MS = 60000;

    private NoiseFrames() {}

    private static byte[] encodeFrame(long chunkId, int index, int total, byte[] payload) {
        Out o = new Out();
        if (chunkId != 0) o.varintField(1, chunkId);
        if (index != 0) o.varintField(2, index);
        o.varintField(3, total); // total is always >= 1
        if (payload.length > 0) o.bytesField(4, payload);
        return o.toBytes();
    }

    /** Split {@code data} into serialized NoiseTransportFrame chunks (an empty payload yields one empty chunk). */
    public static List<byte[]> encode(byte[] data, long chunkId) {
        int total = Math.max(1, (data.length + MAX_CHUNK_PAYLOAD - 1) / MAX_CHUNK_PAYLOAD);
        if (total > MAX_TOTAL_CHUNKS) {
            throw new IllegalArgumentException("payload too large for noise framing (" + data.length + " bytes)");
        }
        List<byte[]> frames = new ArrayList<byte[]>(total);
        for (int i = 0; i < total; i++) {
            int start = i * MAX_CHUNK_PAYLOAD;
            int end = Math.min(data.length, start + MAX_CHUNK_PAYLOAD);
            frames.add(encodeFrame(chunkId, i, total, Arrays.copyOfRange(data, start, end)));
        }
        return frames;
    }

    private static final class Assembly {
        final byte[][] chunks;
        final int total;
        int received, totalBytes;
        final long createdAt;

        Assembly(int total, long now) { this.total = total; chunks = new byte[total][]; createdAt = now; }
    }

    /** Reassembler. After any failure it is poisoned (like the SDK) and the Noise connection should be dropped. */
    public static final class Decoder {
        private final Map<Long, Assembly> pending = new HashMap<Long, Assembly>();
        private long pendingBytes;
        private boolean poisoned;

        private void drop(Long chunkId) {
            Assembly a = pending.remove(chunkId);
            if (a != null) pendingBytes -= a.totalBytes;
        }

        /** @return the full payload once every chunk of a message arrived, else null */
        public byte[] decode(byte[] frameBytes) {
            return decode(frameBytes, System.currentTimeMillis());
        }

        byte[] decode(byte[] frameBytes, long now) {
            if (poisoned) throw new IllegalStateException("NoiseFrames.Decoder: poisoned after prior failure");
            try {
                In in = new In(frameBytes);
                long chunkId = 0;
                long index = 0, total = 1;
                byte[] payload = new byte[0];
                while (in.more()) {
                    in.next();
                    switch (in.field) {
                        case 1: in.expect(Proto.WIRE_VARINT, "chunk_id"); chunkId = in.varint(); break;
                        case 2: in.expect(Proto.WIRE_VARINT, "chunk_index"); index = in.varint(); break;
                        case 3: in.expect(Proto.WIRE_VARINT, "total_chunks"); total = in.varint(); break;
                        case 4: in.expect(Proto.WIRE_DELIMITED, "payload"); payload = in.delimited(); break;
                        default: in.skip();
                    }
                }
                if (total < 1 || total > MAX_TOTAL_CHUNKS) throw new IllegalArgumentException("invalid totalChunks: " + total);
                if (index < 0 || index >= total) throw new IllegalArgumentException("chunkIndex out of range");
                if (payload.length > MAX_CHUNK_PAYLOAD) throw new IllegalArgumentException("payload too large for noise frame");

                for (Iterator<Assembly> it = pending.values().iterator(); it.hasNext();) {
                    Assembly old = it.next();
                    if (now - old.createdAt > ASSEMBLY_TTL_MS) { pendingBytes -= old.totalBytes; it.remove(); }
                }
                Assembly a = pending.get(chunkId);
                if (a == null) {
                    if (pending.size() >= MAX_PENDING_ASSEMBLIES) throw new IllegalArgumentException("too many pending assemblies");
                    a = new Assembly((int) total, now);
                    pending.put(chunkId, a);
                }
                if (a.total != total) {
                    drop(chunkId);
                    throw new IllegalArgumentException("inconsistent totalChunks for chunkId");
                }
                if (a.chunks[(int) index] != null) {
                    drop(chunkId);
                    throw new IllegalArgumentException("duplicate chunkIndex " + index);
                }
                a.totalBytes += payload.length;
                pendingBytes += payload.length;
                if (a.totalBytes > MAX_ASSEMBLY_BYTES) {
                    drop(chunkId);
                    throw new IllegalArgumentException("assembly exceeded byte budget");
                }
                if (pendingBytes > MAX_PENDING_BYTES) {
                    drop(chunkId);
                    throw new IllegalArgumentException("pending assemblies exceeded total byte budget");
                }
                a.chunks[(int) index] = payload;
                if (++a.received < a.total) return null;
                drop(chunkId);
                byte[] out = new byte[a.totalBytes];
                int p = 0;
                for (byte[] c : a.chunks) { System.arraycopy(c, 0, out, p, c.length); p += c.length; }
                return out;
            } catch (RuntimeException e) {
                poisoned = true;
                throw e;
            }
        }
    }
}
