package dev.musegadget.core.util;

import java.util.Base64;

/** base64url without padding (the pairing wire format). Decoding is strict, like the SDK's. */
public final class B64Url {
    private B64Url() {}

    public static String encode(byte[] b) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    /** @throws IllegalArgumentException on anything that is not valid unpadded base64url */
    public static byte[] decode(Object text) {
        return decode(text, 4096);
    }

    /** Same rules as the SDK's {@code b64url_decode}: 1..maxChars chars of [A-Za-z0-9_-], length % 4 != 1. */
    public static byte[] decode(Object text, int maxChars) {
        if (!(text instanceof String)) throw new IllegalArgumentException("not a string");
        String s = (String) text;
        if (s.isEmpty() || s.length() > maxChars) throw new IllegalArgumentException("invalid length");
        if (s.length() % 4 == 1) throw new IllegalArgumentException("invalid base64url");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '_';
            if (!ok) throw new IllegalArgumentException("invalid base64url");
        }
        return Base64.getUrlDecoder().decode(s);
    }
}
