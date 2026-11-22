/*
 * Derived from the Muse Gadgets SDK (https://github.com/facebookincubator/muse-gadget-sdk),
 * Copyright (c) Meta Platforms, Inc. and affiliates, licensed under the Apache License, Version 2.0.
 * This file re-implements the SDK's Noise transport behaviour in Java (not a verbatim copy); see NOTICE.
 */
package dev.musegadget.core.noise;

import com.southernstorm.noise.protocol.CipherStatePair;
import com.southernstorm.noise.protocol.HandshakeState;

import javax.crypto.BadPaddingException;
import javax.crypto.ShortBufferException;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

/**
 * Noise_XX_25519_AESGCM_SHA256 handshake over noise-java.
 * Initiator role is the gadget (msg1 out, msg2 in, msg3 out with an EMPTY payload); the responder role exists
 * for loopback tests and plays the VM. Static keys are generated fresh per handshake and never persisted.
 * msg2's payload is read and discarded by the initiator, as the SDK client does.
 */
public final class NoiseHandshake {
    public static final String PROTOCOL = "Noise_XX_25519_AESGCM_SHA256";
    private static final int BUF = 1024;

    private final HandshakeState hs;
    private final boolean initiator;
    private int step;

    private NoiseHandshake(boolean initiator) {
        try {
            hs = new HandshakeState(PROTOCOL, initiator ? HandshakeState.INITIATOR : HandshakeState.RESPONDER);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("noise-java cannot provide " + PROTOCOL, e);
        }
        this.initiator = initiator;
        hs.getLocalKeyPair().generateKeyPair();
        hs.start(); // empty prologue
    }

    public static NoiseHandshake initiator() { return new NoiseHandshake(true); }

    /** Test/loopback role only. */
    public static NoiseHandshake responder() { return new NoiseHandshake(false); }

    private byte[] write(byte[] payload) throws ShortBufferException {
        if (hs.getAction() != HandshakeState.WRITE_MESSAGE) throw new IllegalStateException("not our turn to write");
        byte[] buf = new byte[BUF];
        int n = hs.writeMessage(buf, 0, payload, 0, payload == null ? 0 : payload.length);
        step++;
        return Arrays.copyOf(buf, n);
    }

    private byte[] read(byte[] msg) throws ShortBufferException, BadPaddingException {
        if (hs.getAction() != HandshakeState.READ_MESSAGE) throw new IllegalStateException("not our turn to read");
        if (msg.length > 65535) throw new IllegalArgumentException("noise message too long");
        byte[] payload = new byte[msg.length];
        int n = hs.readMessage(msg, 0, msg.length, payload, 0);
        step++;
        return Arrays.copyOf(payload, n);
    }

    // -- initiator --
    public byte[] writeMessage1() throws ShortBufferException { requireInitiator(); return write(null); }

    /** @return the (discarded by the gadget) msg2 payload */
    public byte[] readMessage2(byte[] msg) throws ShortBufferException, BadPaddingException { requireInitiator(); return read(msg); }

    /** msg3: encrypted static key plus an empty encrypted payload. */
    public byte[] writeMessage3() throws ShortBufferException { requireInitiator(); return write(null); }

    // -- responder (tests) --
    public void readMessage1(byte[] msg) throws ShortBufferException, BadPaddingException { requireResponder(); read(msg); }

    public byte[] writeMessage2(byte[] payload) throws ShortBufferException { requireResponder(); return write(payload); }

    /** @return the msg3 payload (empty for the gadget) */
    public byte[] readMessage3(byte[] msg) throws ShortBufferException, BadPaddingException { requireResponder(); return read(msg); }

    public boolean complete() { return hs.getAction() == HandshakeState.SPLIT; }

    /** After the third message: initiator send = k1/recv = k2, responder reversed (noise-java's split does this). */
    public NoiseTransport split() {
        if (!complete()) throw new IllegalStateException("handshake not finished");
        CipherStatePair p = hs.split();
        hs.destroy();
        return new NoiseTransport(p.getSender(), p.getReceiver());
    }

    private void requireInitiator() { if (!initiator) throw new IllegalStateException("initiator-only call"); }

    private void requireResponder() { if (initiator) throw new IllegalStateException("responder-only call"); }
}
