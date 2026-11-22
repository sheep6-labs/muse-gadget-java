[English](README.md) | [日本語](README.ja.md)

# muse-gadget-java

Protocol building blocks for turning an Android device into a voice-conversation gadget for Muse — pure Java, no native code.

## What this is

`muse-gadget-java` is a pure-Java re-implementation of the protocol core of the
[Muse Gadgets SDK](https://github.com/facebookincubator/muse-gadget-sdk). It implements the Muse Gadgets
protocol — BLE pairing, the Noise transport and the `/link-control` link — so that a gadget (the device side
of the protocol) can be written on a JVM or on Android. It is extracted from code that runs end-to-end in an
Android app on a Pixel 8a: pairing, Noise connection and voice conversation.

It is an **independent, unofficial** project, not a product of Meta Platforms, Inc., and is licensed under
Apache-2.0 like the upstream SDK. Attribution and trademark details are in [NOTICE](NOTICE).

## Upstream base

Re-implemented from the Muse Gadgets SDK at commit `7b302e3` (2026-11-12), mainly its `linux/`
implementation. What was changed relative to the SDK, the intentional divergences, and how to check
upstream drift since that base are documented in [NOTICE](NOTICE).

## Demo

A working Android gadget built on this core, in conversation: [demo post on X](https://x.com/sakura_beers/status/2124302946074829072).

## Scope

Included (package `dev.musegadget.core`):

- BLE pairing v5 cryptography and protocol handling, device side (`pairing`)
- Noise_XX transport: handshake, envelope, frame chunking (`noise`)
- `/link-control` link framing and request-body upload rules (`link`)
- Text utilities: sentence splitting and streaming (`text`)
- Small helpers: HKDF, hex, base64url, JSON (`util`, `json`)

Not included: any application, audio/voice handling, speech synthesis (e.g. VOICEVOX), UI, and the BLE /
WebSocket I/O itself. The library has no threads and does no I/O: you feed bytes in and send the bytes that
come out.

## Requirements

- A JDK, version 8 or newer (the code is compiled at Java 8 source level; the build is exercised with JDK 17).
- Pure Java with no Android dependency, so it runs on a regular JVM and on Android alike.
- One dependency: [noise-java](https://github.com/rweather/noise-java) (MIT License). It is not bundled;
  `build-and-test.sh` clones it at a pinned commit into `build-deps/`. When you use the library in your own
  project, add noise-java's sources or jar yourself.

## Host requirements

The library does no I/O and makes no policy decisions, so the application embedding it (the "host") must:

- **Open pairing only on an explicit user action.** Accept BLE pairing messages only inside a time window that
  the user opened on purpose (for example, a "pair now" button), and close it when pairing completes or times out.
  A peer that completes pairing is given the SDK token you configured. The BLE encryption protects the channel
  but does not authenticate who the peer is: the trust boundary is that user-opened window.
- **Rate-limit pairing attempts.** Throttling repeated attempts is the host's responsibility. This matters because
  every new `pairing_client_hello` discards any pairing session still in progress, so an unthrottled or ungated peer
  could keep resetting a legitimate pairing.
- **Reassemble BLE messages and cap them at 8,192 bytes.** Joining BLE fragments into a message happens in the host,
  which should cap the reassembled message at 8,192 bytes while it buffers. As a second line of defense,
  `PairingProtocol.onMessage` rejects raw input larger than `PairingProtocol.MAX_MESSAGE_BYTES` (8,192) before
  parsing it.
- **Know what the Noise layer does not authenticate.** The library does not pin the peer's static Noise key.
  Authenticating the server relies on the outer WSS/TLS connection and the service token, so the host must make that
  connection to the intended service endpoint over verified TLS.
- **Privacy note.** `get_device_info` is answered in plaintext before pairing and includes the device identifier
  (this is how the upstream protocol behaves). Anyone in BLE range can read it.
- **Manage Wi-Fi yourself.** Connecting to a network belongs to the host (the OS or your app). A `provision_v2`
  message carries an SSID and password, but `PairingProtocol.Provision` returns only the service credentials
  (`accessToken`, `refreshToken`, `username`, `apiUrl`, `apiUrlV2`, `noiseHost`); the Wi-Fi fields are validated
  for presence and then dropped. Call `setNetworkReady(true)` once the host is online: `wifi_scan` then
  reports a single "Use current connection" entry.
- **Protect the SDK token.** Never log or display it; `SdkToken.status` returns a language-neutral set/unset value you can render in your own UI.

### Android

The sources are kept at Java 8 source level so that older Android toolchains and devices can use them. The
library has been used in an app on real devices running Android 10 (API 29) or later; older versions have not
been verified.

## Build & test

    ./build-and-test.sh

The script needs `git` and a JDK (set `JAVA_HOME` if the JDK is not on `PATH`). It clones noise-java,
compiles everything (library, tests and `examples/`) with `-source 8`, runs all tests, including the official
SDK pairing test vectors bundled in `tests/vectors/`, and runs the example. It ends with
`CORE: ALL TESTS PASSED`.

## How to use

Main classes:

| Package | Class | Role |
|---|---|---|
| `pairing` | `PairingSession` | One BLE pairing v5 session (device side): hello, key agreement, encrypted records |
| `pairing` | `PairingProtocol` | Message dispatcher on top of a session; returns what to send and any `Provision` credentials |
| `pairing` | `SdkToken` | Format check for an SDK token, and a safe set/unset status |
| `noise` | `NoiseHandshake` | `Noise_XX_25519_AESGCM_SHA256` handshake; `initiator()` is the gadget |
| `noise` | `NoiseTransport` | Encrypts requests, decrypts server frames, handles chunking |
| `link` | `LinkFraming` | `/link-control` framing: length-prefixed JSON messages, keepalives |
| `link` | `Upload` | Request-body size limits and 16 KiB chunking |
| `text` | `Sentences`, `SentenceStream` | Sentence splitting for whole text / streamed deltas |

The flow is pairing, then a Noise connection, then the link. Sketch (I/O and error handling omitted):

```java
import dev.musegadget.core.pairing.*;
import dev.musegadget.core.noise.*;
import dev.musegadget.core.link.LinkFraming;

// 1. BLE pairing: feed each reassembled app-to-device BLE message, send what comes back
PairingSession session = PairingSession.create(nodeId, deviceId, mac, firmwareVersion, sdkToken);
PairingProtocol pairing = new PairingProtocol(session, nodeId, firmwareVersion);

PairingProtocol.Output out = pairing.onMessage(bleMessage);   // byte[]
for (String reply : out.send) { /* write reply to the BLE characteristic */ }
if (out.provision != null) { /* out.provision.accessToken, .noiseHost, ... */ }
if (out.disconnect) { /* drop the BLE link shortly after sending */ }
// pairing.onDisconnect() when the link drops

// 2. Noise_XX over your WebSocket (one binary frame = one Noise message)
NoiseHandshake hs = NoiseHandshake.initiator();
byte[] msg1 = hs.writeMessage1();      // send
hs.readMessage2(receivedMsg2);         // receive
byte[] msg3 = hs.writeMessage3();      // send
NoiseTransport noise = hs.split();

// 3. /link-control: open a stream, then send length-prefixed JSON messages
NoiseTransport.Encrypted stream = noise.openStream("POST", "/link-control", null);
// send stream.frames, then:
byte[] framed = LinkFraming.encode("{\"type\":\"...\"}");
for (byte[] frame : noise.bodyChunk(stream.streamId, framed, false)) { /* send frame */ }

LinkFraming.Decoder decoder = new LinkFraming.Decoder();
Envelope.ServiceFrame f = noise.decryptFrame(receivedWsFrame); // null until a whole message arrives
if (f != null && f.bodyChunk != null) {
    for (Map<String, Object> message : decoder.feed(f.bodyChunk.data)) { /* handle */ }
}
```

[`examples/MinimalExample.java`](examples/MinimalExample.java) is a complete, compilable program (no network)
that runs a Noise handshake and exchanges one `/link-control` message each way. The tests in `tests/` (for
example `NoiseLoopbackTest`, `LinkFramingTest`) are runnable usages of each part.

## Service note

Using the Muse Gadgets service requires your own SDK token, which you can get at
<https://gadgets.muse.ai/settings/sdk-tokens>, and is subject to the service's terms.
This library does not contain or provide any token or credentials.

## Status

Extracted from code that has been verified end to end in an Android app on a Pixel 8a (Android 10 / API 29
or later is the supported baseline; see [Host requirements](#host-requirements)). This repository contains
only the protocol core; no JAR or release artifact is published yet. APIs may change.

Speech in that app is handled outside this library. One data point for builders who add it (measured on a
Pixel 8a, November 2026): on-device English TTS with sherpa-onnx and a Piper voice (en_US-amy-medium) runs
at RTF 0.11–0.14, with first audio in about 0.2–0.3 s, when replies are split into sentences with this
library's `text` package and synthesized sentence by sentence. Pin sherpa-onnx to the 1.x line (verified
with 1.13.8): 2.x no longer bundles the espeak-ng phonemizer, and Piper voices then fail silently,
returning empty audio instead of raising an error.

## License

Apache License 2.0 — see [LICENSE](LICENSE) and [NOTICE](NOTICE). noise-java is MIT-licensed and is
fetched at build time, not bundled.
