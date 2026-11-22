/*
 * Derived from the Muse Gadgets SDK (https://github.com/facebookincubator/muse-gadget-sdk),
 * Copyright (c) Meta Platforms, Inc. and affiliates, licensed under the Apache License, Version 2.0.
 * This file re-implements the SDK's BLE pairing v5 behaviour in Java (not a verbatim copy); see NOTICE.
 */
package dev.musegadget.core.pairing;

import dev.musegadget.core.json.Json;
import dev.musegadget.core.util.B64Url;

import java.nio.charset.Charset;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Device side of one BLE pairing v5 session, ported from
 * SDK linux/src/musegadget/pairing.py {@code PairingSession} (community / confirm_app only).
 * No I/O and no threads: the caller feeds messages in and sends what comes back.
 * Not thread-safe; drive it from one thread. Any decrypt failure discards the whole session.
 */
public final class PairingSession {
    public static final String ERROR_INVALID_HELLO = "error_pairing_invalid_hello";
    public static final String ERROR_DECRYPT = "error_pairing_decrypt";
    public static final long CLIENT_FINISHED_TIMEOUT_MS = 60000;
    public static final long CONFIRMED_TIMEOUT_MS = 120000;
    public static final long PROVISIONING_TIMEOUT_MS = 120000;
    private static final int MAX_CIPHERTEXT_B64_CHARS = 16384;
    private static final Charset UTF8 = Charset.forName("UTF-8");

    public enum State { IDLE, WAIT_CLIENT_FINISHED, READY, PROVISIONING }

    /** Source of the per-hello ephemeral device key (tests inject a fixed one). */
    public interface KeySource {
        PairingCrypto.DeviceKey newKey() throws GeneralSecurityException;
    }

    /** Source of the 16-byte device nonce. */
    public interface NonceSource {
        byte[] nonce(int n);
    }

    private final String nodeId, deviceId, mac, firmwareVersion;
    private volatile String sdkToken; // optional; may be set after construction (entered in the UI)
    private final LongSupplier clockMs;
    private final KeySource keys;
    private final NonceSource nonces;

    private State state = State.IDLE;
    private long deadline;
    private byte[] rxKey, txKey; // device receive (= mobile_tx) / device send (= mobile_rx)
    private String sessionIdB64 = "";
    private long rxCounter, txCounter;

    public PairingSession(String nodeId, String deviceId, String mac, String firmwareVersion, String sdkToken,
            LongSupplier clockMs, KeySource keys, NonceSource nonces) {
        this.nodeId = nodeId;
        this.deviceId = deviceId;
        this.mac = mac;
        this.firmwareVersion = (firmwareVersion == null || firmwareVersion.isEmpty()) ? "unknown" : firmwareVersion;
        this.sdkToken = sdkToken;
        this.clockMs = clockMs;
        this.keys = keys;
        this.nonces = nonces;
    }

    /** Production wiring: system clock, fresh ephemeral P-256 key per hello, SecureRandom nonces. */
    public static PairingSession create(String nodeId, String deviceId, String mac, String firmwareVersion,
            String sdkToken) {
        final java.security.SecureRandom rnd = new java.security.SecureRandom();
        return new PairingSession(nodeId, deviceId, mac, firmwareVersion, sdkToken,
                new LongSupplier() {
                    public long getAsLong() { return System.nanoTime() / 1000000L; }
                },
                new KeySource() {
                    public PairingCrypto.DeviceKey newKey() throws GeneralSecurityException {
                        return PairingCrypto.generateKey();
                    }
                },
                new NonceSource() {
                    public byte[] nonce(int n) { byte[] b = new byte[n]; rnd.nextBytes(b); return b; }
                });
    }

    /** Set or clear (null/empty) the token presented on the next {@code pairing_confirmed}. Not validated here: see {@link SdkToken}. */
    public void setSdkToken(String token) { sdkToken = token == null || token.isEmpty() ? null : token; }

    /** Fields for the plaintext {@code get_device_info} response. */
    public Map<String, Object> deviceInfo() {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("device_id", deviceId);
        m.put("mac", mac);
        m.put("model", PairingCrypto.MODEL);
        m.put("pairing_protocol", (long) PairingCrypto.VERSION);
        m.put("pairing_auth", PairingCrypto.AUTH_COMMUNITY);
        m.put("pairing_auth_epoch", 0L);
        m.put("pairing_policy", PairingCrypto.POLICY_APP);
        return m;
    }

    public State state() {
        expire();
        return state;
    }

    public boolean confirmed() {
        State s = state();
        return s == State.READY || s == State.PROVISIONING;
    }

    public String sessionId() { return sessionIdB64; }

    public void reset() { clear(); }

    /** {@code pairing_client_hello} -> {@code pairing_ready}. Starts a fresh session, discarding any old one. */
    public Map<String, Object> handleHello(Map<String, Object> hello) throws PairingException {
        Object version = hello.get("version");
        if (!(version instanceof Number) || ((Number) version).doubleValue() != PairingCrypto.VERSION
                || !PairingCrypto.AUTH_COMMUNITY.equals(hello.get("pairing_auth"))
                || !PairingCrypto.POLICY_APP.equals(hello.get("pairing_policy"))) {
            throw new PairingException(ERROR_INVALID_HELLO);
        }
        clear();
        byte[] mobilePub, mobileNonce;
        try {
            mobilePub = B64Url.decode(hello.get("mobile_pub"));
            mobileNonce = B64Url.decode(hello.get("mobile_nonce"));
            if (mobilePub.length != PairingCrypto.POINT_BYTES || mobilePub[0] != 0x04
                    || mobileNonce.length != PairingCrypto.NONCE_BYTES) {
                throw new IllegalArgumentException("invalid hello key material");
            }
            PairingCrypto.decodePoint(mobilePub); // on-curve check up front, as from_encoded_point does
        } catch (IllegalArgumentException e) {
            throw new PairingException(ERROR_INVALID_HELLO);
        } catch (GeneralSecurityException e) {
            throw new PairingException(ERROR_INVALID_HELLO);
        }
        try {
            PairingCrypto.DeviceKey key = keys.newKey();
            byte[] devicePub = key.publicPoint();
            byte[] deviceNonce = nonces.nonce(PairingCrypto.NONCE_BYTES);
            String transcript = PairingCrypto.buildTranscript(true, 0, PairingCrypto.POLICY_APP,
                    deviceId, nodeId, mac, firmwareVersion,
                    B64Url.encode(mobilePub), B64Url.encode(devicePub),
                    B64Url.encode(mobileNonce), B64Url.encode(deviceNonce));
            byte[] th = PairingCrypto.transcriptHash(transcript);
            byte[] ecdh = key.ecdh(mobilePub);
            PairingCrypto.Keys k = PairingCrypto.deriveSessionKeys(ecdh, mobileNonce, deviceNonce, th);
            Arrays.fill(ecdh, (byte) 0);

            rxKey = k.mobileTx;
            txKey = k.mobileRx;
            sessionIdB64 = B64Url.encode(k.sessionId);
            rxCounter = 0;
            txCounter = 0;
            state = State.WAIT_CLIENT_FINISHED;
            deadline = clockMs.getAsLong() + CLIENT_FINISHED_TIMEOUT_MS;

            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("type", "pairing_ready");
            m.put("version", (long) PairingCrypto.VERSION);
            m.put("device_id", deviceId);
            m.put("node_id", nodeId);
            m.put("mac", mac);
            m.put("model", PairingCrypto.MODEL);
            m.put("firmware_version", firmwareVersion);
            m.put("pairing_auth", PairingCrypto.AUTH_COMMUNITY);
            m.put("pairing_auth_epoch", 0L);
            m.put("pairing_policy", PairingCrypto.POLICY_APP);
            m.put("device_pub", B64Url.encode(devicePub));
            m.put("device_nonce", B64Url.encode(deviceNonce));
            m.put("transcript_hash", B64Url.encode(th));
            m.put("session_id", sessionIdB64);
            return m;
        } catch (GeneralSecurityException e) {
            clear();
            throw new PairingException(ERROR_INVALID_HELLO);
        }
    }

    /**
     * Open one app-to-device {@code pairing_encrypted} record and return its plaintext. Any failure (wrong
     * session, skipped/replayed counter, bad tag, expiry) clears the session and throws
     * {@link #ERROR_DECRYPT}.
     */
    public String decrypt(Map<String, Object> envelope) throws PairingException {
        if (expire() || state == State.IDLE) {
            clear();
            throw new PairingException(ERROR_DECRYPT);
        }
        try {
            if (!sessionIdB64.equals(envelope.get("session_id"))) throw new IllegalArgumentException("wrong session");
            Object c = envelope.get("counter");
            if (!(c instanceof String) || !((String) c).matches("[0-9]+")) throw new IllegalArgumentException("counter");
            long counter = Long.parseUnsignedLong((String) c); // NumberFormatException (an IAE) beyond 2^64-1
            if (counter != rxCounter) throw new IllegalArgumentException("unexpected counter");
            byte[] ct = B64Url.decode(envelope.get("ciphertext"), MAX_CIPHERTEXT_B64_CHARS);
            byte[] tag = B64Url.decode(envelope.get("tag"));
            if (tag.length != PairingCrypto.TAG_BYTES) throw new IllegalArgumentException("tag length");
            byte[] sealed = new byte[ct.length + tag.length];
            System.arraycopy(ct, 0, sealed, 0, ct.length);
            System.arraycopy(tag, 0, sealed, ct.length, tag.length);
            byte[] pt = PairingCrypto.open(rxKey, PairingCrypto.TO_DEVICE, counter, sessionIdB64, sealed);
            String text = new String(pt, UTF8);
            rxCounter++;
            return text;
        } catch (IllegalArgumentException e) {
            clear();
            throw new PairingException(ERROR_DECRYPT);
        } catch (GeneralSecurityException e) {
            clear();
            throw new PairingException(ERROR_DECRYPT);
        }
    }

    /**
     * Confirm after the first record. It must be exactly {@code {"action":"pairing_client_finished"}} and
     * the first record received; otherwise the session is cleared and false returned.
     */
    public boolean handleClientFinished(Map<String, Object> command) {
        boolean ok = command.size() == 1
                && "pairing_client_finished".equals(command.get("action"))
                && !expire()
                && state == State.WAIT_CLIENT_FINISHED
                && rxCounter == 1;
        if (!ok) {
            clear();
            return false;
        }
        state = State.READY;
        deadline = clockMs.getAsLong() + CONFIRMED_TIMEOUT_MS;
        return true;
    }

    /** Enter provisioning from a confirmed session. */
    public boolean markProvisioning() {
        if (!expire() && state == State.READY) {
            state = State.PROVISIONING;
            deadline = clockMs.getAsLong() + PROVISIONING_TIMEOUT_MS;
        }
        return state == State.PROVISIONING;
    }

    /** Stay in provisioning for another window (e.g. after a recoverable failure). */
    public boolean extendProvisioning() {
        if (!expire() && state == State.PROVISIONING) {
            deadline = clockMs.getAsLong() + PROVISIONING_TIMEOUT_MS;
            return true;
        }
        return false;
    }

    /** Seal a device-to-app record; null if there is no live session. */
    public Map<String, Object> encryptJson(String plaintext) {
        if (expire() || state == State.IDLE) return null;
        long counter = txCounter;
        byte[] sealed;
        try {
            sealed = PairingCrypto.seal(txKey, PairingCrypto.FROM_DEVICE, counter, sessionIdB64, plaintext.getBytes(UTF8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
        txCounter++;
        int n = sealed.length - PairingCrypto.TAG_BYTES;
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("type", "pairing_encrypted");
        m.put("session_id", sessionIdB64);
        m.put("counter", Long.toUnsignedString(counter));
        m.put("ciphertext", B64Url.encode(Arrays.copyOfRange(sealed, 0, n)));
        m.put("tag", B64Url.encode(Arrays.copyOfRange(sealed, n, sealed.length)));
        return m;
    }

    /** Seal {@code {"type":"status","status":...}}; the SDK token rides only on {@code pairing_confirmed}. */
    public Map<String, Object> encryptStatus(String status) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("type", "status");
        m.put("status", status);
        String token = sdkToken;
        if (token != null && !token.isEmpty() && "pairing_confirmed".equals(status)) m.put("sdk_token", token);
        return encryptJson(Json.write(m));
    }

    private boolean expire() {
        if (state == State.IDLE || clockMs.getAsLong() <= deadline) return false;
        clear();
        return true;
    }

    private void clear() {
        state = State.IDLE;
        deadline = 0;
        if (rxKey != null) Arrays.fill(rxKey, (byte) 0);
        if (txKey != null) Arrays.fill(txKey, (byte) 0);
        rxKey = txKey = null;
        sessionIdB64 = "";
        rxCounter = txCounter = 0;
    }
}
