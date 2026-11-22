package dev.musegadget.core.util;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;

/**
 * HKDF-SHA256 (RFC 5869). Pure Java, no Android dependency.
 */
public final class Hkdf {
    private static final String HMAC = "HmacSHA256";
    private static final int HASH_LEN = 32;

    private Hkdf() {}

    public static byte[] hmac(byte[] key, byte[] data) throws GeneralSecurityException {
        Mac mac = Mac.getInstance(HMAC);
        // SecretKeySpec rejects empty keys; HMAC with an empty key equals a zero-filled HashLen key.
        mac.init(new SecretKeySpec(key.length == 0 ? new byte[HASH_LEN] : key, HMAC));
        return mac.doFinal(data);
    }

    /** Extract: PRK = HMAC(salt, IKM). A null/empty salt is HashLen zero bytes. */
    public static byte[] extract(byte[] salt, byte[] ikm) throws GeneralSecurityException {
        return hmac(salt == null ? new byte[HASH_LEN] : salt, ikm);
    }

    /** Expand: OKM of {@code length} bytes (max 255*HashLen). */
    public static byte[] expand(byte[] prk, byte[] info, int length) throws GeneralSecurityException {
        if (length < 0 || length > 255 * HASH_LEN) throw new IllegalArgumentException("length");
        if (info == null) info = new byte[0];
        byte[] okm = new byte[length];
        byte[] t = new byte[0];
        int pos = 0;
        for (int i = 1; pos < length; i++) {
            byte[] in = new byte[t.length + info.length + 1];
            System.arraycopy(t, 0, in, 0, t.length);
            System.arraycopy(info, 0, in, t.length, info.length);
            in[in.length - 1] = (byte) i;
            t = hmac(prk, in);
            int n = Math.min(t.length, length - pos);
            System.arraycopy(t, 0, okm, pos, n);
            pos += n;
        }
        return okm;
    }

    public static byte[] derive(byte[] salt, byte[] ikm, byte[] info, int length)
            throws GeneralSecurityException {
        return expand(extract(salt, ikm), info, length);
    }
}
