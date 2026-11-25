import dev.musegadget.core.json.Json;
import dev.musegadget.core.pairing.PairingCrypto;
import dev.musegadget.core.pairing.PairingProtocol;
import dev.musegadget.core.pairing.PairingSession;
import dev.musegadget.core.pairing.SdkToken;
import dev.musegadget.core.util.B64Url;
import dev.musegadget.core.util.Hex;

import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Official pairing v5 vectors (SDK linux/tests/vectors/link_pairing_v5.json) against PairingCrypto and PairingSession. */
public final class PairingVectorTest {
    static final Charset UTF8 = Charset.forName("UTF-8");

    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        String path = args.length > 0 ? args[0]
                : "tests/vectors/link_pairing_v5.json";
        Map<String, Object> doc = Json.parseObject(new String(Files.readAllBytes(Paths.get(path)), UTF8));
        List<Object> vectors = (List<Object>) doc.get("vectors");
        T.check("vector file has 5 vectors", vectors.size() == 5, "n=" + vectors.size());
        for (Object o : vectors) crypto((Map<String, Object>) o);
        for (Object o : vectors) {
            Map<String, Object> v = (Map<String, Object>) o;
            if ("community_app_v5".equals(v.get("name"))) session(v);
        }
        for (Object o : vectors) {
            Map<String, Object> v = (Map<String, Object>) o;
            if ("community_app_v5".equals(v.get("name"))) sessionNegatives(v);
        }
        for (Object o : vectors) {
            Map<String, Object> v = (Map<String, Object>) o;
            if ("community_app_v5".equals(v.get("name"))) sdkToken(v);
        }
        T.done();
    }

    static final String VALID_TOKEN = "mgst_" + "AbCdEfGhIjKlMnOpQrStUvWxYz0123456789_-abcd".substring(0, 42) + "A";

    /** Run hello + client_finished on {@code p} and return the decrypted pairing_confirmed plaintext (exact JSON text). */
    static String confirmedPlaintext(Map<String, Object> v, PairingProtocol p) throws Exception {
        p.onMessage(Json.write(hello(v)).getBytes(UTF8));
        PairingProtocol.Output f = p.onMessage(Json.write(finishedRecord(v)).getBytes(UTF8));
        Map<String, Object> env = Json.parseObject(f.send.get(0));
        byte[] key = Hex.decode(s(v, "mobile_rx_key_hex"));
        return new String(PairingCrypto.open(key, PairingCrypto.FROM_DEVICE, 0, s(v, "session_id"),
                concat(B64Url.decode(env.get("ciphertext")), B64Url.decode(env.get("tag")))), UTF8);
    }

    /** SDK token on pairing_confirmed: same shape as the SDK, absent when unset (the vector-verified behaviour), settable late. */
    static void sdkToken(Map<String, Object> v) throws Exception {
        String n = "sdk_token: ";
        long[] clock = {0};
        T.check(n + "test token is well formed", SdkToken.isValid(VALID_TOKEN), VALID_TOKEN.length() + " chars");

        PairingSession none = new PairingSession(s(v, "node_id"), s(v, "device_id"), s(v, "mac"), s(v, "firmware_version"), null,
                () -> clock[0], () -> PairingCrypto.keyFromScalar(Hex.decode(s(v, "device_private_scalar_hex")), B64Url.decode(s(v, "device_pub"))),
                x -> B64Url.decode(s(v, "device_nonce")));
        T.eq(n + "unset: exact pre-existing plaintext", "{\"type\":\"status\",\"status\":\"pairing_confirmed\"}",
                confirmedPlaintext(v, new PairingProtocol(none, s(v, "node_id"), s(v, "firmware_version"))));

        PairingSession s1 = new PairingSession(s(v, "node_id"), s(v, "device_id"), s(v, "mac"), s(v, "firmware_version"), null,
                () -> clock[0], () -> PairingCrypto.keyFromScalar(Hex.decode(s(v, "device_private_scalar_hex")), B64Url.decode(s(v, "device_pub"))),
                x -> B64Url.decode(s(v, "device_nonce")));
        PairingProtocol late = new PairingProtocol(s1, s(v, "node_id"), s(v, "firmware_version"));
        late.setSdkToken(VALID_TOKEN);
        T.eq(n + "set after construction: token rides pairing_confirmed",
                "{\"type\":\"status\",\"status\":\"pairing_confirmed\",\"sdk_token\":\"" + VALID_TOKEN + "\"}",
                confirmedPlaintext(v, late));

        PairingSession s2 = new PairingSession(s(v, "node_id"), s(v, "device_id"), s(v, "mac"), s(v, "firmware_version"), VALID_TOKEN,
                () -> clock[0], () -> PairingCrypto.keyFromScalar(Hex.decode(s(v, "device_private_scalar_hex")), B64Url.decode(s(v, "device_pub"))),
                x -> B64Url.decode(s(v, "device_nonce")));
        PairingProtocol cleared = new PairingProtocol(s2, s(v, "node_id"), s(v, "firmware_version"));
        cleared.setSdkToken("");
        T.eq(n + "cleared again: back to the plain status",
                "{\"type\":\"status\",\"status\":\"pairing_confirmed\"}", confirmedPlaintext(v, cleared));

        // After the handshake the session is live; other statuses never carry the token.
        Map<String, Object> env = s1.encryptStatus("auth_ok");
        byte[] key = Hex.decode(s(v, "mobile_rx_key_hex"));
        String authOk = new String(PairingCrypto.open(key, PairingCrypto.FROM_DEVICE, 1, s(v, "session_id"),
                concat(B64Url.decode(env.get("ciphertext")), B64Url.decode(env.get("tag")))), UTF8);
        T.eq(n + "auth_ok never carries the token", "{\"type\":\"status\",\"status\":\"auth_ok\"}", authOk);

        // Format check (config.py _SDK_TOKEN: mgst_ + 42 chars + canonical last char).
        T.check(n + "valid format", SdkToken.isValid(VALID_TOKEN));
        T.check(n + "wrong prefix", !SdkToken.isValid("mgsx_" + VALID_TOKEN.substring(5)));
        T.check(n + "too short", !SdkToken.isValid(VALID_TOKEN.substring(0, VALID_TOKEN.length() - 1)));
        T.check(n + "too long", !SdkToken.isValid(VALID_TOKEN + "A"));
        T.check(n + "non-canonical last char", !SdkToken.isValid(VALID_TOKEN.substring(0, VALID_TOKEN.length() - 1) + "B"));
        T.check(n + "bad character", !SdkToken.isValid("mgst_" + "!" + VALID_TOKEN.substring(6)));
        T.check(n + "null/empty", !SdkToken.isValid(null) && !SdkToken.isValid(""));
        T.check(n + "normalize trims paste whitespace", SdkToken.isValid(SdkToken.normalize("  " + VALID_TOKEN + "\n")));

        // Safe display forms.
        T.check(n + "status set", SdkToken.status(VALID_TOKEN) == SdkToken.Status.SET);
        T.check(n + "status unset (empty and null)", SdkToken.status("") == SdkToken.Status.UNSET && SdkToken.status(null) == SdkToken.Status.UNSET);
    }

    static String s(Map<String, Object> v, String k) { return (String) v.get(k); }

    static void crypto(Map<String, Object> v) throws Exception {
        String n = s(v, "name") + ": ";
        boolean community = (Boolean) v.get("community");
        String transcript = PairingCrypto.buildTranscript(community, ((Number) v.get("pairing_auth_epoch")).intValue(),
                s(v, "pairing_policy"), s(v, "device_id"), s(v, "node_id"), s(v, "mac"), s(v, "firmware_version"),
                s(v, "mobile_pub"), s(v, "device_pub"), s(v, "mobile_nonce"), s(v, "device_nonce"));
        T.eq(n + "transcript", s(v, "transcript"), transcript);
        byte[] th = PairingCrypto.transcriptHash(transcript);
        T.eq(n + "transcript_hash", s(v, "transcript_hash"), B64Url.encode(th));

        byte[] mobilePub = B64Url.decode(s(v, "mobile_pub")), devicePub = B64Url.decode(s(v, "device_pub"));
        PairingCrypto.DeviceKey dev = PairingCrypto.keyFromScalar(Hex.decode(s(v, "device_private_scalar_hex")), devicePub);
        PairingCrypto.DeviceKey mob = PairingCrypto.keyFromScalar(Hex.decode(s(v, "mobile_private_scalar_hex")), mobilePub);
        byte[] ecdh = dev.ecdh(mobilePub);
        T.eq(n + "ecdh_secret (device side)", s(v, "ecdh_secret_hex"), Hex.encode(ecdh));
        T.eq(n + "ecdh_secret (mobile side)", s(v, "ecdh_secret_hex"), Hex.encode(mob.ecdh(devicePub)));

        byte[] mn = B64Url.decode(s(v, "mobile_nonce")), dn = B64Url.decode(s(v, "device_nonce"));
        PairingCrypto.Keys k = PairingCrypto.deriveSessionKeys(ecdh, mn, dn, th);
        T.eq(n + "session_secret", s(v, "session_secret_hex"), Hex.encode(k.sessionSecret));
        T.eq(n + "mobile_tx_key", s(v, "mobile_tx_key_hex"), Hex.encode(k.mobileTx));
        T.eq(n + "mobile_rx_key", s(v, "mobile_rx_key_hex"), Hex.encode(k.mobileRx));
        T.eq(n + "session_id", s(v, "session_id"), B64Url.encode(k.sessionId));

        String sid = s(v, "session_id");
        T.eq(n + "client_finished aad", s(v, "client_finished_aad"),
                new String(PairingCrypto.recordAad(sid, PairingCrypto.TO_DEVICE, 0), UTF8));
        byte[] sealed = concat(B64Url.decode(s(v, "client_finished_ciphertext")), B64Url.decode(s(v, "client_finished_tag")));
        byte[] pt = PairingCrypto.open(k.mobileTx, PairingCrypto.TO_DEVICE, 0, sid, sealed);
        T.eq(n + "client_finished decrypts + authenticates", s(v, "client_finished_plaintext"), new String(pt, UTF8));
        byte[] re = PairingCrypto.seal(k.mobileTx, PairingCrypto.TO_DEVICE, 0, sid, s(v, "client_finished_plaintext").getBytes(UTF8));
        T.check(n + "re-seal reproduces ciphertext||tag", Arrays.equals(re, sealed));
        sealed[0] ^= 1;
        boolean rejected = false;
        try { PairingCrypto.open(k.mobileTx, PairingCrypto.TO_DEVICE, 0, sid, sealed); } catch (java.security.GeneralSecurityException e) { rejected = true; }
        T.check(n + "tampered ciphertext rejected", rejected);
    }

    /** Fixed device key + nonce so the live session must reproduce the vector. */
    static PairingSession sessionFor(final Map<String, Object> v, final long[] clock) {
        return new PairingSession(s(v, "node_id"), s(v, "device_id"), s(v, "mac"), s(v, "firmware_version"), "mgst_test",
                () -> clock[0],
                () -> PairingCrypto.keyFromScalar(Hex.decode(s(v, "device_private_scalar_hex")), B64Url.decode(s(v, "device_pub"))),
                n -> B64Url.decode(s(v, "device_nonce")));
    }

    static Map<String, Object> hello(Map<String, Object> v) {
        Map<String, Object> h = new LinkedHashMap<String, Object>();
        h.put("action", "pairing_client_hello");
        h.put("version", 5L);
        h.put("pairing_auth", "none");
        h.put("pairing_policy", "confirm_app");
        h.put("mobile_pub", s(v, "mobile_pub"));
        h.put("mobile_nonce", s(v, "mobile_nonce"));
        return h;
    }

    static Map<String, Object> finishedRecord(Map<String, Object> v) {
        Map<String, Object> e = new LinkedHashMap<String, Object>();
        e.put("action", "pairing_encrypted");
        e.put("session_id", s(v, "session_id"));
        e.put("counter", "0");
        e.put("ciphertext", s(v, "client_finished_ciphertext"));
        e.put("tag", s(v, "client_finished_tag"));
        return e;
    }

    @SuppressWarnings("unchecked")
    static void session(Map<String, Object> v) throws Exception {
        String n = "session(community_app_v5): ";
        long[] clock = {0};
        PairingProtocol p = new PairingProtocol(sessionFor(v, clock), s(v, "node_id"), s(v, "firmware_version"));
        p.setNetworkReady(true);

        PairingProtocol.Output info = p.onMessage("{\"action\":\"get_device_info\"}".getBytes(UTF8));
        Map<String, Object> im = Json.parseObject(info.send.get(0));
        T.check(n + "get_device_info (plaintext)", "device_info".equals(im.get("type")) && "confirm_app".equals(im.get("pairing_policy"))
                && Boolean.TRUE.equals(im.get("network_ready")) && ((Number) im.get("pairing_protocol")).intValue() == 5);

        PairingProtocol.Output r = p.onMessage(Json.write(hello(v)).getBytes(UTF8));
        Map<String, Object> ready = Json.parseObject(r.send.get(0));
        T.eq(n + "pairing_ready.type", "pairing_ready", (String) ready.get("type"));
        T.eq(n + "pairing_ready.device_pub", s(v, "device_pub"), (String) ready.get("device_pub"));
        T.eq(n + "pairing_ready.transcript_hash", s(v, "transcript_hash"), (String) ready.get("transcript_hash"));
        T.eq(n + "pairing_ready.session_id", s(v, "session_id"), (String) ready.get("session_id"));

        PairingProtocol.Output f = p.onMessage(Json.write(finishedRecord(v)).getBytes(UTF8));
        T.check(n + "client_finished accepted -> READY", p.session().state() == PairingSession.State.READY && !f.disconnect);
        Map<String, Object> env = Json.parseObject(f.send.get(0));
        T.eq(n + "reply is device->app record", "pairing_encrypted", (String) env.get("type"));
        T.eq(n + "reply counter 0", "0", (String) env.get("counter"));
        byte[] key = Hex.decode(s(v, "mobile_rx_key_hex"));
        byte[] pt = PairingCrypto.open(key, PairingCrypto.FROM_DEVICE, 0, s(v, "session_id"),
                concat(B64Url.decode(env.get("ciphertext")), B64Url.decode(env.get("tag"))));
        Map<String, Object> st = Json.parseObject(new String(pt, UTF8));
        T.check(n + "status pairing_confirmed (+sdk_token)", "pairing_confirmed".equals(st.get("status")) && "mgst_test".equals(st.get("sdk_token")));

        // Mobile side seals wifi_scan with the same schedule (counter 1) and gets a sealed wifi_scan_result.
        byte[] txKey = Hex.decode(s(v, "mobile_tx_key_hex"));
        Map<String, Object> scan = record(txKey, s(v, "session_id"), 1, "{\"action\":\"wifi_scan\"}");
        PairingProtocol.Output w = p.onMessage(Json.write(scan).getBytes(UTF8));
        Map<String, Object> wenv = Json.parseObject(w.send.get(0));
        T.eq(n + "wifi_scan reply counter 1", "1", (String) wenv.get("counter"));
        Map<String, Object> wres = Json.parseObject(new String(PairingCrypto.open(key, PairingCrypto.FROM_DEVICE, 1, s(v, "session_id"),
                concat(B64Url.decode(wenv.get("ciphertext")), B64Url.decode(wenv.get("tag")))), UTF8));
        T.check(n + "wifi_scan_result has current connection", "wifi_scan_result".equals(wres.get("type")) && ((List<Object>) wres.get("networks")).size() == 1);

        String prov = "{\"action\":\"provision_v2\",\"ssid\":\"x\",\"password\":\"y\",\"access_token\":\"a\",\"refresh_token\":\"r\","
                + "\"token_type\":\"device\",\"username\":\"u\",\"api_url\":\"\",\"api_url_v2\":\"https://api.example\",\"noise_host\":\"h.example\"}";
        PairingProtocol.Output pr = p.onMessage(Json.write(record(txKey, s(v, "session_id"), 2, prov)).getBytes(UTF8));
        T.check(n + "provision_v2 validated -> PROVISIONING",
                pr.provision != null && "a".equals(pr.provision.accessToken) && "h.example".equals(pr.provision.noiseHost)
                        && p.session().state() == PairingSession.State.PROVISIONING);
    }

    @SuppressWarnings("unchecked")
    static void sessionNegatives(Map<String, Object> v) throws Exception {
        String n = "session negatives: ";
        // plaintext sensitive action before pairing -> bare status string
        long[] clock = {0};
        PairingProtocol p = new PairingProtocol(sessionFor(v, clock), s(v, "node_id"), "1.0.0");
        PairingProtocol.Output o = p.onMessage("{\"action\":\"wifi_scan\"}".getBytes(UTF8));
        T.check(n + "plaintext wifi_scan -> error_encryption_required (bare)", o.send.size() == 1 && o.send.get(0).equals("error_encryption_required"));
        o = p.onMessage("{\"action\":\"pairing_client_hello\",\"version\":4,\"pairing_auth\":\"none\",\"pairing_policy\":\"confirm_app\"}".getBytes(UTF8));
        T.check(n + "wrong version -> error_pairing_invalid_hello", o.send.size() == 1 && o.send.get(0).equals("error_pairing_invalid_hello"));
        o = p.onMessage("{\"action\":\"pairing_client_hello\",\"version\":5,\"pairing_auth\":\"fleet_ecdsa_p256_v1\",\"pairing_policy\":\"confirm_app\"}".getBytes(UTF8));
        T.check(n + "official auth refused", o.send.get(0).equals("error_pairing_invalid_hello"));

        // oversized raw input is rejected before JSON parsing: a valid get_device_info padded past the limit gets no
        // reply (the same message at exactly the limit is answered)
        byte[] jsonInfo = "{\"action\":\"get_device_info\"}".getBytes(UTF8);
        byte[] atLimit = new byte[PairingProtocol.MAX_MESSAGE_BYTES];
        java.util.Arrays.fill(atLimit, (byte) ' ');
        System.arraycopy(jsonInfo, 0, atLimit, 0, jsonInfo.length);
        byte[] overLimit = java.util.Arrays.copyOf(atLimit, atLimit.length + 1);
        overLimit[atLimit.length] = (byte) ' ';
        PairingProtocol fresh = new PairingProtocol(sessionFor(v, clock), s(v, "node_id"), "1.0.0");
        T.check(n + "message of exactly the limit is still handled", fresh.onMessage(atLimit).send.size() == 1);
        T.check(n + "over-limit raw message is dropped before parsing", fresh.onMessage(overLimit).send.isEmpty());

        // bad mobile point (not on curve)
        Map<String, Object> bad = hello(v);
        byte[] pt = B64Url.decode(s(v, "mobile_pub")); pt[64] ^= 1;
        bad.put("mobile_pub", B64Url.encode(pt));
        o = p.onMessage(Json.write(bad).getBytes(UTF8));
        T.check(n + "off-curve mobile_pub rejected", o.send.get(0).equals("error_pairing_invalid_hello"));

        // counter skip discards the session and disconnects
        p = new PairingProtocol(sessionFor(v, clock), s(v, "node_id"), "1.0.0");
        p.onMessage(Json.write(hello(v)).getBytes(UTF8));
        Map<String, Object> skip = finishedRecord(v);
        skip.put("counter", "1");
        o = p.onMessage(Json.write(skip).getBytes(UTF8));
        T.check(n + "skipped counter -> session destroyed + disconnect", o.disconnect && p.session().state() == PairingSession.State.IDLE);

        // replay of record #0 after acceptance
        p = new PairingProtocol(sessionFor(v, clock), s(v, "node_id"), "1.0.0");
        p.onMessage(Json.write(hello(v)).getBytes(UTF8));
        p.onMessage(Json.write(finishedRecord(v)).getBytes(UTF8));
        o = p.onMessage(Json.write(finishedRecord(v)).getBytes(UTF8));
        T.check(n + "replayed record -> session destroyed", o.disconnect && p.session().state() == PairingSession.State.IDLE);

        // client_finished with extra field is not "exactly" the finished command
        p = new PairingProtocol(sessionFor(v, clock), s(v, "node_id"), "1.0.0");
        p.onMessage(Json.write(hello(v)).getBytes(UTF8));
        byte[] txKey = Hex.decode(s(v, "mobile_tx_key_hex"));
        o = p.onMessage(Json.write(record(txKey, s(v, "session_id"), 0, "{\"action\":\"pairing_client_finished\",\"x\":1}")).getBytes(UTF8));
        T.check(n + "finished with extra field rejected", o.disconnect && p.session().state() == PairingSession.State.IDLE);

        // sensitive action as first record: decrypts fine, but the session is still unconfirmed
        p = new PairingProtocol(sessionFor(v, clock), s(v, "node_id"), "1.0.0");
        p.onMessage(Json.write(hello(v)).getBytes(UTF8));
        o = p.onMessage(Json.write(record(txKey, s(v, "session_id"), 0, "{\"action\":\"wifi_scan\"}")).getBytes(UTF8));
        Map<String, Object> env = Json.parseObject(o.send.get(0));
        byte[] rx = Hex.decode(s(v, "mobile_rx_key_hex"));
        Map<String, Object> st = Json.parseObject(new String(PairingCrypto.open(rx, PairingCrypto.FROM_DEVICE, 0, s(v, "session_id"),
                concat(B64Url.decode(env.get("ciphertext")), B64Url.decode(env.get("tag")))), UTF8));
        T.check(n + "unconfirmed wifi_scan -> error_pairing_confirm_required", "error_pairing_confirm_required".equals(st.get("status")));

        // client_finished timeout (60 s)
        clock[0] = 0;
        p = new PairingProtocol(sessionFor(v, clock), s(v, "node_id"), "1.0.0");
        p.onMessage(Json.write(hello(v)).getBytes(UTF8));
        clock[0] = 60001;
        o = p.onMessage(Json.write(finishedRecord(v)).getBytes(UTF8));
        T.check(n + "client_finished after 60 s -> discarded", o.disconnect && p.session().state() == PairingSession.State.IDLE);
    }

    /** Mobile-app side: seal an app->device record. */
    static Map<String, Object> record(byte[] key, String sid, long counter, String plaintext) throws Exception {
        byte[] sealed = PairingCrypto.seal(key, PairingCrypto.TO_DEVICE, counter, sid, plaintext.getBytes(UTF8));
        int n = sealed.length - 16;
        Map<String, Object> e = new LinkedHashMap<String, Object>();
        e.put("action", "pairing_encrypted");
        e.put("session_id", sid);
        e.put("counter", Long.toString(counter));
        e.put("ciphertext", B64Url.encode(Arrays.copyOfRange(sealed, 0, n)));
        e.put("tag", B64Url.encode(Arrays.copyOfRange(sealed, n, sealed.length)));
        return e;
    }

    static byte[] concat(byte[] a, byte[] b) {
        byte[] r = new byte[a.length + b.length];
        System.arraycopy(a, 0, r, 0, a.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }
}
