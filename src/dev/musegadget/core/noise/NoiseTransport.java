/*
 * Derived from the Muse Gadgets SDK (https://github.com/facebookincubator/muse-gadget-sdk),
 * Copyright (c) Meta Platforms, Inc. and affiliates, licensed under the Apache License, Version 2.0.
 * This file re-implements the SDK's Noise transport behaviour in Java (not a verbatim copy); see NOTICE.
 */
package dev.musegadget.core.noise;

import com.southernstorm.noise.protocol.CipherState;
import dev.musegadget.core.noise.Envelope.ApplicationRequest;
import dev.musegadget.core.noise.Envelope.ApplicationResponse;
import dev.musegadget.core.noise.Envelope.BodyChunk;
import dev.musegadget.core.noise.Envelope.Header;
import dev.musegadget.core.noise.Envelope.Kind;
import dev.musegadget.core.noise.Envelope.Reset;
import dev.musegadget.core.noise.Envelope.ServiceFrame;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

/**
 * Post-handshake transport, mirroring SDK noise/transport.py {@code NoiseTransport}.
 * One WebSocket binary frame = one Noise transport message (empty AD) carrying one NoiseTransportFrame chunk
 * of a ServiceRequest/ServiceResponse. Gadget side sends requests and receives responses; the
 * {@code peer*} methods are the mirror image, used by the loopback test to play the VM.
 * Stream ids are assigned from 1. After any failure the transport is dead.
 */
public final class NoiseTransport {
    /** Largest Noise message (ciphertext including the tag), per the Noise specification. */
    public static final int MAX_NOISE_MESSAGE = 65535;

    private final CipherState send, recv;
    private final NoiseFrames.Decoder decoder = new NoiseFrames.Decoder();
    private final SecureRandom rnd = new SecureRandom();
    private long nextStreamId = 1;
    private boolean dead;

    NoiseTransport(CipherState send, CipherState recv) {
        this.send = send;
        this.recv = recv;
    }

    /** Frames to put on the wire; {@code streamId} identifies the stream for follow-up chunks. */
    public static final class Encrypted {
        public final long streamId;
        public final List<byte[]> frames;
        Encrypted(long id, List<byte[]> f) { streamId = id; frames = f; }
    }

    // ---- gadget side ----

    /** A complete request (end_body = true). */
    public Encrypted request(String verb, String path, byte[] body, List<Header> headers) {
        return open(verb, path, body, headers, true);
    }

    /** Open a streaming request (end_body = false), e.g. {@code POST /link-control}. */
    public Encrypted openStream(String verb, String path, List<Header> headers) {
        return open(verb, path, new byte[0], headers, false);
    }

    private Encrypted open(String verb, String path, byte[] body, List<Header> headers, boolean end) {
        alive();
        try {
            long id = nextStreamId++;
            ApplicationRequest r = new ApplicationRequest();
            r.verb = verb;
            r.path = path;
            if (headers != null) r.headers = headers;
            r.body = body;
            r.endBody = end;
            return new Encrypted(id, encryptRequest(ServiceFrame.request(id, r)));
        } catch (RuntimeException e) {
            dead = true;
            throw e;
        }
    }

    public List<byte[]> bodyChunk(long streamId, byte[] data, boolean endBody) {
        alive();
        try {
            return encryptRequest(ServiceFrame.bodyChunk(streamId, new BodyChunk(data, endBody)));
        } catch (RuntimeException e) {
            dead = true;
            throw e;
        }
    }

    public List<byte[]> reset(long streamId, int code, String reason) {
        alive();
        try {
            return encryptRequest(ServiceFrame.reset(streamId, new Reset(code, reason)));
        } catch (RuntimeException e) {
            dead = true;
            throw e;
        }
    }

    /**
     * Decrypt one incoming WebSocket frame.
     * @return the server's frame (response / body_chunk / reset) once a whole message is reassembled, else null
     */
    public ServiceFrame decryptFrame(byte[] ciphertext) {
        alive();
        try {
            byte[] whole = decoder.decode(decrypt(ciphertext));
            if (whole == null) return null;
            byte[] payload = Envelope.decodeServiceResponse(whole);
            if (payload.length == 0) throw new IllegalStateException("empty ServiceResponse payload");
            ServiceFrame f = Envelope.decodeFrame(payload);
            if (f.kind == Kind.REQUEST) throw new IllegalStateException("unexpected request frame from server");
            return f.kind == Kind.NONE ? null : f;
        } catch (RuntimeException e) {
            dead = true;
            throw e;
        }
    }

    // ---- VM side (loopback tests) ----

    /** Decrypt one frame from the gadget; returns the ServiceFrame once reassembled, else null. */
    public ServiceFrame peerDecryptFrame(byte[] ciphertext) {
        alive();
        try {
            byte[] whole = decoder.decode(decrypt(ciphertext));
            if (whole == null) return null;
            return Envelope.decodeFrame(Envelope.decodeServiceRequest(whole).payload);
        } catch (RuntimeException e) {
            dead = true;
            throw e;
        }
    }

    public List<byte[]> peerEncrypt(ServiceFrame frame) {
        alive();
        try {
            return encryptChunks(Envelope.encodeServiceResponse(Envelope.encodeFrame(frame)));
        } catch (RuntimeException e) {
            dead = true;
            throw e;
        }
    }

    // ---- internals ----

    private List<byte[]> encryptRequest(ServiceFrame f) {
        return encryptChunks(Envelope.encodeServiceRequest(Envelope.SERVICE_DAEMON, Envelope.encodeFrame(f)));
    }

    private List<byte[]> encryptChunks(byte[] message) {
        List<byte[]> out = new ArrayList<byte[]>();
        for (byte[] chunk : NoiseFrames.encode(message, rnd.nextLong())) {
            try {
                byte[] ct = new byte[chunk.length + send.getMACLength()];
                int n = send.encryptWithAd(null, chunk, 0, ct, 0, chunk.length);
                out.add(java.util.Arrays.copyOf(ct, n));
            } catch (javax.crypto.ShortBufferException e) {
                throw new IllegalStateException(e);
            }
        }
        return out;
    }

    private byte[] decrypt(byte[] ct) {
        if (ct.length > MAX_NOISE_MESSAGE) {
            throw new IllegalStateException("noise message too large: " + ct.length + " bytes");
        }
        try {
            byte[] pt = new byte[ct.length];
            int n = recv.decryptWithAd(null, ct, 0, pt, 0, ct.length);
            return java.util.Arrays.copyOf(pt, n);
        } catch (javax.crypto.ShortBufferException e) {
            throw new IllegalStateException(e);
        } catch (javax.crypto.BadPaddingException e) {
            throw new IllegalStateException("noise decrypt failed", e);
        }
    }

    private void alive() {
        if (dead) throw new IllegalStateException("NoiseTransport: dead after prior failure");
    }

    public static ApplicationResponse okResponse() {
        ApplicationResponse r = new ApplicationResponse();
        r.status = 200;
        return r;
    }
}
