/*
 * Derived from the Muse Gadgets SDK (https://github.com/facebookincubator/muse-gadget-sdk),
 * Copyright (c) Meta Platforms, Inc. and affiliates, licensed under the Apache License, Version 2.0.
 * This file re-implements the SDK's BLE pairing v5 behaviour in Java (not a verbatim copy); see NOTICE.
 */
package dev.musegadget.core.pairing;

import dev.musegadget.core.json.Json;

import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Message dispatcher on top of {@link PairingSession}, mirroring SDK linux ble_setup.py {@code handle_message}.
 * Input: one fully reassembled app-to-device BLE message (UTF-8 JSON). Output: what to send back (each entry
 * is one complete BLE message: JSON text, or a bare status string for the four plaintext statuses), whether
 * to disconnect shortly after, and a validated provisioning request for the caller to execute.
 * No BLE I/O, no threads. Call {@link #onDisconnect()} when the link drops.
 */
public final class PairingProtocol {
    private static final Set<String> SENSITIVE = new HashSet<String>(Arrays.asList(
            "provision", "provision_v2", "wifi_scan", "ota", "device.ota", "unpair", "set_wifi", "set_auth"));
    private static final Set<String> PLAINTEXT_STATUSES = new HashSet<String>(Arrays.asList(
            "error_encryption_required", "error_pairing_invalid_hello",
            "error_pairing_unavailable", "error_pairing_decrypt"));
    private static final Charset UTF8 = Charset.forName("UTF-8");

    /** Credentials from a valid {@code provision_v2}; Wi-Fi fields are dropped. */
    public static final class Provision {
        public final String accessToken, refreshToken, username, apiUrl, apiUrlV2, noiseHost;

        Provision(Map<String, Object> c) {
            accessToken = text(c, "access_token");
            refreshToken = text(c, "refresh_token");
            username = text(c, "username");
            apiUrl = text(c, "api_url");
            apiUrlV2 = text(c, "api_url_v2");
            noiseHost = text(c, "noise_host");
        }
    }

    public static final class Output {
        /** Messages to send, in order. Statuses sent encrypted are already sealed envelopes. */
        public final List<String> send = new ArrayList<String>();
        /** True when the link should be dropped (~0.3 s after sending, as the SDK does). */
        public boolean disconnect;
        /** Non-null when a valid provision_v2 arrived and the session is now PROVISIONING. */
        public Provision provision;
    }

    private final PairingSession session;
    private final String nodeId, firmwareVersion;
    private boolean networkReady;
    private boolean plaintextBlocked;

    public PairingProtocol(PairingSession session, String nodeId, String firmwareVersion) {
        this.session = session;
        this.nodeId = nodeId;
        this.firmwareVersion = firmwareVersion;
    }

    public void setNetworkReady(boolean ready) { networkReady = ready; }

    public PairingSession session() { return session; }

    /** Token for the next {@code pairing_confirmed} (null/empty: none, the status stays as before). */
    public void setSdkToken(String token) { session.setSdkToken(token); }

    public void onDisconnect() {
        plaintextBlocked = false;
        session.reset();
    }

    /** Largest raw pairing message accepted (bytes), checked before parsing; matches the host's BLE reassembly cap. */
    public static final int MAX_MESSAGE_BYTES = 8192;

    public Output onMessage(byte[] raw) {
        Output out = new Output();
        handle(raw, false, out);
        return out;
    }

    /** Seal a status string for sending after async work (e.g. {@code auth_ok}); null if the session is gone. */
    public String sealStatus(String status) {
        Map<String, Object> env = session.encryptStatus(status);
        return env == null ? null : Json.write(env);
    }

    private void handle(byte[] raw, boolean decrypted, Output out) {
        if (raw.length > MAX_MESSAGE_BYTES) {
            status("error_invalid_command", out);
            return;
        }
        Map<String, Object> cmd;
        try {
            String text = UTF8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(raw)).toString();
            cmd = Json.parseObject(text);
        } catch (CharacterCodingException e) {
            status("error_invalid_command", out);
            return;
        } catch (Json.JsonException e) {
            status("error_invalid_command", out);
            return;
        }
        Object a = cmd.get("action");
        String action = a instanceof String ? (String) a : "";

        if (!decrypted && action.equals("pairing_client_hello")) {
            hello(cmd, out);
        } else if (!decrypted && action.equals("pairing_encrypted")) {
            record(cmd, out);
        } else if (action.equals("get_device_info")) {
            out.send.add(Json.write(deviceInfo()));
        } else if (!decrypted && plaintextBlocked) {
            // plaintext after the handshake started is ignored
        } else if (!decrypted && SENSITIVE.contains(action)) {
            status("error_encryption_required", out);
        } else if (decrypted && action.equals("pairing_client_finished")) {
            if (session.handleClientFinished(cmd)) {
                status("pairing_confirmed", out);
            } else {
                status("error_pairing_decrypt", out);
                out.disconnect = true;
            }
        } else if (decrypted && SENSITIVE.contains(action) && !session.confirmed()) {
            status("error_pairing_confirm_required", out);
        } else if (decrypted && action.equals("wifi_scan")) {
            wifiScan(out);
        } else if (decrypted && action.equals("provision_v2")) {
            provision(cmd, out);
        } else {
            status("error_unknown_action", out);
        }
    }

    private Map<String, Object> deviceInfo() {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("type", "device_info");
        m.put("node_id", nodeId);
        m.put("version", firmwareVersion);
        m.putAll(session.deviceInfo());
        m.put("build_sha", "");
        m.put("network_ready", networkReady);
        return m;
    }

    private void hello(Map<String, Object> cmd, Output out) {
        try {
            Map<String, Object> ready = session.handleHello(cmd);
            plaintextBlocked = true;
            out.send.add(Json.write(ready));
        } catch (PairingException e) {
            status(e.status, out);
        }
    }

    private void record(Map<String, Object> envelope, Output out) {
        String plaintext;
        try {
            plaintext = session.decrypt(envelope);
        } catch (PairingException e) {
            status(e.status, out);
            out.disconnect = true;
            return;
        }
        handle(plaintext.getBytes(UTF8), true, out);
    }

    private void wifiScan(Output out) {
        List<Object> networks = new ArrayList<Object>();
        if (networkReady) {
            Map<String, Object> n = new LinkedHashMap<String, Object>();
            n.put("ssid", "Use current connection");
            n.put("rssi", -40L);
            n.put("secure", false);
            networks.add(n);
        }
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("type", "wifi_scan_result");
        m.put("networks", networks);
        Map<String, Object> env = session.encryptJson(Json.write(m));
        if (env != null) out.send.add(Json.write(env));
    }

    private void provision(Map<String, Object> c, Output out) {
        if (!(c.get("ssid") instanceof String) || !(c.get("password") instanceof String)
                || text(c, "access_token").isEmpty() || text(c, "refresh_token").isEmpty()
                || !"device".equals(text(c, "token_type"))) {
            status("error_missing_credentials", out);
            return;
        }
        if (session.state() == PairingSession.State.PROVISIONING) {
            status("error_operation_in_progress", out);
            return;
        }
        if (!session.markProvisioning()) {
            status("error_pairing_confirm_required", out);
            return;
        }
        out.provision = new Provision(c);
    }

    private static String text(Map<String, Object> c, String k) {
        Object v = c.get(k);
        return v instanceof String ? (String) v : "";
    }

    /** Encrypted when a session is live; otherwise only the four plaintext statuses go out, as bare strings. */
    private void status(String status, Output out) {
        Map<String, Object> env = session.encryptStatus(status);
        if (env != null) {
            out.send.add(Json.write(env));
        } else if (!plaintextBlocked && PLAINTEXT_STATUSES.contains(status)) {
            out.send.add(status);
        }
    }
}
