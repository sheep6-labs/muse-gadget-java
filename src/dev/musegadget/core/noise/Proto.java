/*
 * Derived from the Muse Gadgets SDK (https://github.com/facebookincubator/muse-gadget-sdk),
 * Copyright (c) Meta Platforms, Inc. and affiliates, licensed under the Apache License, Version 2.0.
 * This file re-implements the SDK's Noise transport behaviour in Java (not a verbatim copy); see NOTICE.
 */
package dev.musegadget.core.noise;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;

/**
 * Hand-written protobuf wire primitives (varint / length-delimited), matching SDK noise/_proto.py.
 * Fields are only emitted when non-default by the callers, like the SDK encoders.
 */
final class Proto {
    static final int WIRE_VARINT = 0, WIRE_FIXED64 = 1, WIRE_DELIMITED = 2, WIRE_FIXED32 = 5;
    static final Charset UTF8 = Charset.forName("UTF-8");

    private Proto() {}

    static final class ProtoException extends RuntimeException {
        ProtoException(String m) { super(m); }
    }

    /** Output buffer with field writers. */
    static final class Out {
        final ByteArrayOutputStream b = new ByteArrayOutputStream();

        void varint(long v) { // v is treated as unsigned 64-bit
            while ((v & ~0x7FL) != 0) {
                b.write((int) ((v & 0x7F) | 0x80));
                v >>>= 7;
            }
            b.write((int) v);
        }

        void key(int field, int wire) { varint(((long) field << 3) | wire); }

        void varintField(int field, long v) { key(field, WIRE_VARINT); varint(v); }

        void int32Field(int field, int v) { varintField(field, (long) v); } // negative sign-extends to 10 bytes

        void boolField(int field, boolean v) { varintField(field, v ? 1 : 0); }

        void bytesField(int field, byte[] v) {
            key(field, WIRE_DELIMITED);
            varint(v.length);
            b.write(v, 0, v.length);
        }

        void stringField(int field, String s) { bytesField(field, s.getBytes(UTF8)); }

        byte[] toBytes() { return b.toByteArray(); }
    }

    /** Cursor over a message. */
    static final class In {
        final byte[] d;
        int pos;
        int field, wire;

        In(byte[] d) { this.d = d; }

        boolean more() { return pos < d.length; }

        long varint() {
            long v = 0;
            for (int i = 0; i < 10; i++) {
                if (pos >= d.length) throw new ProtoException("truncated varint");
                int x = d[pos++] & 0xFF;
                if (i == 9 && (x & 0xFE) != 0) throw new ProtoException("malformed varint");
                v |= (long) (x & 0x7F) << (7 * i);
                if ((x & 0x80) == 0) return v;
            }
            throw new ProtoException("malformed varint");
        }

        /** Reads the next key into {@link #field}/{@link #wire}. */
        void next() {
            long k = varint();
            long f = k >>> 3;
            field = (int) f;
            wire = (int) (k & 7);
            if (f == 0 || f > (1L << 29) - 1 || (f >= 19000 && f <= 19999)) throw new ProtoException("invalid field number");
            if (wire != WIRE_VARINT && wire != WIRE_FIXED64 && wire != WIRE_DELIMITED && wire != WIRE_FIXED32) {
                throw new ProtoException("invalid wire type");
            }
        }

        byte[] delimited() {
            long len = varint();
            if (len < 0 || len > d.length - pos) throw new ProtoException("truncated delimited field");
            byte[] r = new byte[(int) len];
            System.arraycopy(d, pos, r, 0, r.length);
            pos += r.length;
            return r;
        }

        String string() {
            try {
                return UTF8.newDecoder().decode(java.nio.ByteBuffer.wrap(delimited())).toString();
            } catch (java.nio.charset.CharacterCodingException e) {
                throw new ProtoException("invalid utf-8 string");
            }
        }

        void expect(int wantWire, String what) {
            if (wire != wantWire) throw new ProtoException(what + " wrong wire type");
        }

        void skip() {
            switch (wire) {
                case WIRE_VARINT: varint(); break;
                case WIRE_DELIMITED: delimited(); break;
                case WIRE_FIXED64: advance(8); break;
                default: advance(4);
            }
        }

        private void advance(int n) {
            if (pos + n > d.length) throw new ProtoException("truncated fixed field");
            pos += n;
        }

        static int int32(long raw) {
            if (raw < Integer.MIN_VALUE || raw > Integer.MAX_VALUE) throw new ProtoException("int32 out of range");
            return (int) raw;
        }
    }
}
