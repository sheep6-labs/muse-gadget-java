/*
 * Derived from the Muse Gadgets SDK (https://github.com/facebookincubator/muse-gadget-sdk),
 * Copyright (c) Meta Platforms, Inc. and affiliates, licensed under the Apache License, Version 2.0.
 * This file re-implements the SDK's BLE pairing v5 behaviour in Java (not a verbatim copy); see NOTICE.
 */
package dev.musegadget.core.pairing;

import java.util.regex.Pattern;

/**
 * SDK token (https://gadgets.muse.ai/settings/sdk-tokens): format check and a safe set/unset status.
 * Mirrors SDK linux config.py {@code _SDK_TOKEN}. The token itself is a secret: never log or display
 * it or any part of it; use {@link #status} for a status to display.
 */
public final class SdkToken {
    /** {@code mgst_} + 43 canonical base64url characters (the last one carries only 4 significant bits). */
    private static final Pattern FORMAT = Pattern.compile("mgst_[A-Za-z0-9_-]{42}[AEIMQUYcgkosw048]");

    private SdkToken() {}

    public static boolean isValid(String token) { return token != null && FORMAT.matcher(token).matches(); }

    /** Surrounding whitespace removed (paste artefacts); null stays null. */
    public static String normalize(String input) { return input == null ? null : input.trim(); }

    /** Whether a token is configured. Carries no part of the token; the host decides how to word it for users. */
    public enum Status { SET, UNSET }

    /** Safe, language-neutral status: set/unset only. No fragment of the token is ever exposed. */
    public static Status status(String token) {
        return token == null || token.isEmpty() ? Status.UNSET : Status.SET;
    }
}
