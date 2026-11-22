/*
 * Derived from the Muse Gadgets SDK (https://github.com/facebookincubator/muse-gadget-sdk),
 * Copyright (c) Meta Platforms, Inc. and affiliates, licensed under the Apache License, Version 2.0.
 * This file re-implements the SDK's BLE pairing v5 behaviour in Java (not a verbatim copy); see NOTICE.
 */
package dev.musegadget.core.pairing;

/** A handshake step failed; {@link #status} is the wire status string to report. */
public final class PairingException extends Exception {
    public final String status;

    public PairingException(String status) {
        super(status);
        this.status = status;
    }
}
