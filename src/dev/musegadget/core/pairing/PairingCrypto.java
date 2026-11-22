/*
 * Derived from the Muse Gadgets SDK (https://github.com/facebookincubator/muse-gadget-sdk),
 * Copyright (c) Meta Platforms, Inc. and affiliates, licensed under the Apache License, Version 2.0.
 * This file re-implements the SDK's BLE pairing v5 behaviour in Java (not a verbatim copy); see NOTICE.
 */
package dev.musegadget.core.pairing;

import dev.musegadget.core.util.B64Url;
import dev.musegadget.core.util.Hkdf;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigInteger;
import java.nio.charset.Charset;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPrivateKeySpec;
import java.security.spec.ECPublicKeySpec;

/**
 * Pairing v5 primitives, ported from SDK linux/src/musegadget/pairing.py.
 * Pure JCA (P-256 ECDH, SHA-256, AES-256-GCM, HKDF from {@link Hkdf}); no Android API.
 */
public final class PairingCrypto {
    public static final int VERSION = 5;
    public static final String MODEL = "hatch_link";
    public static final String SUITE = "p256-hkdf-sha256-aes-gcm-v1";
    public static final String POLICY_BUTTON = "confirm_press";
    public static final String POLICY_APP = "confirm_app";
    public static final String AUTH_OFFICIAL = "fleet_ecdsa_p256_v1";
    public static final String AUTH_COMMUNITY = "none";
    public static final int BUTTON_CONFIRM_TIMEOUT_S = 60;
    private static final Charset UTF8 = Charset.forName("UTF-8"); // must precede ascii() users below
    public static final String RECORD_LABEL = "hatch-link ble setup v1";
    private static final byte[] SESSION_ID_LABEL = ascii("hatch-link session id v1");
    public static final int SESSION_ID_BYTES = 16;
    public static final int NONCE_BYTES = 16;
    public static final int POINT_BYTES = 65;
    public static final int TAG_BYTES = 16;
    public static final int TO_DEVICE = 0;   // app -> device ("m2d")
    public static final int FROM_DEVICE = 1; // device -> app ("d2m")

    private static final ECParameterSpec P256 = p256();

    private PairingCrypto() {}

    private static byte[] ascii(String s) { return s.getBytes(UTF8); }

    private static ECParameterSpec p256() {
        try {
            AlgorithmParameters ap = AlgorithmParameters.getInstance("EC");
            ap.init(new ECGenParameterSpec("secp256r1"));
            return ap.getParameterSpec(ECParameterSpec.class);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- transcript / key schedule ----

    /** Canonical v5 transcript. {@code SHA-256(UTF-8(result))} is the transcript hash. */
    public static String buildTranscript(boolean community, int authEpoch, String policy,
            String deviceId, String nodeId, String mac, String firmwareVersion,
            String mobilePub, String devicePub, String mobileNonce, String deviceNonce) {
        boolean button = POLICY_BUTTON.equals(policy);
        if (!(button || (community && POLICY_APP.equals(policy)))) {
            throw new IllegalArgumentException("unsupported pairing policy: " + policy);
        }
        if (community ? authEpoch != 0 : authEpoch <= 0) {
            throw new IllegalArgumentException("invalid auth epoch: " + authEpoch);
        }
        String[] fields = {deviceId, nodeId, mac, firmwareVersion, mobilePub, devicePub, mobileNonce, deviceNonce};
        for (String f : fields) {
            if (f == null || f.isEmpty()) throw new IllegalArgumentException("transcript fields must be non-empty");
        }
        String[] lines = {
            "hatch-link-pairing-v" + VERSION,
            "version=" + VERSION,
            "initiator_role=mobile",
            "responder_role=link",
            "device_id=" + deviceId,
            "node_id=" + nodeId,
            "mac=" + mac,
            "model=" + MODEL,
            "firmware_version=" + firmwareVersion,
            "selected_cipher_suite=" + SUITE,
            "pairing_auth=" + (community ? AUTH_COMMUNITY : AUTH_OFFICIAL),
            "pairing_auth_epoch=" + authEpoch,
            "pairing_policy=" + policy,
            "confirm_timeout_seconds=" + (button ? BUTTON_CONFIRM_TIMEOUT_S : 0),
            "mobile_pub=" + mobilePub,
            "device_pub=" + devicePub,
            "mobile_nonce=" + mobileNonce,
            "device_nonce=" + deviceNonce,
        };
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) sb.append('\n');
            sb.append(lines[i]);
        }
        return sb.toString();
    }

    public static byte[] sha256(byte[]... parts) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (byte[] p : parts) md.update(p);
            return md.digest();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public static byte[] transcriptHash(String transcript) {
        return sha256(transcript.getBytes(UTF8));
    }

    /** Result of {@link #deriveSessionKeys}. {@code mobileTx} decrypts what the device receives; {@code mobileRx} encrypts what it sends. */
    public static final class Keys {
        public final byte[] sessionSecret, mobileTx, mobileRx, sessionId;

        Keys(byte[] secret, byte[] tx, byte[] rx, byte[] id) {
            sessionSecret = secret; mobileTx = tx; mobileRx = rx; sessionId = id;
        }
    }

    public static Keys deriveSessionKeys(byte[] ecdhSecret, byte[] mobileNonce, byte[] deviceNonce,
            byte[] transcriptHash) throws GeneralSecurityException {
        byte[] salt = sha256(mobileNonce, deviceNonce, transcriptHash);
        byte[] secret = Hkdf.derive(salt, ecdhSecret, ascii(RECORD_LABEL), 32);
        byte[] tx = Hkdf.expand(secret, ascii("mobile->device"), 32);
        byte[] rx = Hkdf.expand(secret, ascii("device->mobile"), 32);
        byte[] id = new byte[SESSION_ID_BYTES];
        System.arraycopy(sha256(SESSION_ID_LABEL, transcriptHash, ecdhSecret), 0, id, 0, SESSION_ID_BYTES);
        return new Keys(secret, tx, rx, id);
    }

    // ---- records ----

    public static byte[] recordNonce(int direction, long counter) {
        byte[] n = new byte[12];
        n[0] = (byte) direction;
        for (int i = 0; i < 8; i++) n[4 + i] = (byte) (counter >>> (56 - 8 * i));
        return n;
    }

    public static byte[] recordAad(String sessionIdB64, int direction, long counter) {
        return ascii(RECORD_LABEL + "|" + sessionIdB64 + "|" + (direction == TO_DEVICE ? "m2d" : "d2m")
                + "|" + Long.toUnsignedString(counter));
    }

    /** AES-256-GCM seal; returns ciphertext || 16-byte tag. */
    public static byte[] seal(byte[] key, int direction, long counter, String sessionIdB64, byte[] plaintext)
            throws GeneralSecurityException {
        return gcm(Cipher.ENCRYPT_MODE, key, direction, counter, sessionIdB64, plaintext);
    }

    /** AES-256-GCM open of ciphertext || tag; throws on authentication failure. */
    public static byte[] open(byte[] key, int direction, long counter, String sessionIdB64, byte[] sealed)
            throws GeneralSecurityException {
        return gcm(Cipher.DECRYPT_MODE, key, direction, counter, sessionIdB64, sealed);
    }

    private static byte[] gcm(int mode, byte[] key, int direction, long counter, String sid, byte[] data)
            throws GeneralSecurityException {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(mode, new SecretKeySpec(key, "AES"),
                new GCMParameterSpec(TAG_BYTES * 8, recordNonce(direction, counter)));
        c.updateAAD(recordAad(sid, direction, counter));
        return c.doFinal(data);
    }

    // ---- P-256 ----

    /** A P-256 key pair able to do ECDH; the production one is ephemeral, tests inject fixed scalars. */
    public static final class DeviceKey {
        private final PrivateKey priv;
        private final byte[] pub;

        public DeviceKey(PrivateKey priv, byte[] uncompressedPub) {
            this.priv = priv;
            this.pub = uncompressedPub.clone();
        }

        public byte[] publicPoint() { return pub.clone(); }

        /** Raw ECDH shared secret (32-byte x coordinate) with a peer's 65-byte uncompressed point. */
        public byte[] ecdh(byte[] peerPoint) throws GeneralSecurityException {
            KeyAgreement ka = KeyAgreement.getInstance("ECDH");
            ka.init(priv);
            ka.doPhase(decodePoint(peerPoint), true);
            return ka.generateSecret();
        }
    }

    public static DeviceKey generateKey() throws GeneralSecurityException {
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair kp = g.generateKeyPair();
        ECPoint w = ((ECPublicKey) kp.getPublic()).getW();
        return new DeviceKey(kp.getPrivate(), encodePoint(w));
    }

    /** Test support: build a key from a known scalar and its (separately known) public point. */
    public static DeviceKey keyFromScalar(byte[] scalar, byte[] uncompressedPub) throws GeneralSecurityException {
        PrivateKey k = KeyFactory.getInstance("EC").generatePrivate(new ECPrivateKeySpec(new BigInteger(1, scalar), P256));
        return new DeviceKey(k, uncompressedPub);
    }

    public static byte[] encodePoint(ECPoint w) {
        byte[] out = new byte[POINT_BYTES];
        out[0] = 0x04;
        copyFixed(w.getAffineX(), out, 1);
        copyFixed(w.getAffineY(), out, 33);
        return out;
    }

    private static void copyFixed(BigInteger v, byte[] out, int off) {
        byte[] b = v.toByteArray();
        int start = b.length > 32 ? b.length - 32 : 0;
        System.arraycopy(b, start, out, off + 32 - (b.length - start), b.length - start);
    }

    /** Decode and validate (on-curve, coordinates in range) a 65-byte uncompressed point. */
    public static PublicKey decodePoint(byte[] p) throws GeneralSecurityException {
        if (p.length != POINT_BYTES || p[0] != 0x04) throw new GeneralSecurityException("bad point encoding");
        BigInteger x = new BigInteger(1, java.util.Arrays.copyOfRange(p, 1, 33));
        BigInteger y = new BigInteger(1, java.util.Arrays.copyOfRange(p, 33, 65));
        BigInteger prime = ((java.security.spec.ECFieldFp) P256.getCurve().getField()).getP();
        if (x.compareTo(prime) >= 0 || y.compareTo(prime) >= 0) throw new GeneralSecurityException("coordinate out of range");
        BigInteger lhs = y.multiply(y).mod(prime);
        BigInteger rhs = x.multiply(x).add(P256.getCurve().getA()).multiply(x).add(P256.getCurve().getB()).mod(prime);
        if (!lhs.equals(rhs)) throw new GeneralSecurityException("point not on curve");
        return KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(new ECPoint(x, y), P256));
    }

    public static String b64(byte[] b) { return B64Url.encode(b); }
}
