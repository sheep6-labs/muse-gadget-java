[English](README.md) | [日本語](README.ja.md)

# muse-gadget-java

Android 端末を Muse の音声会話ガジェットにするためのプロトコル部品です。純 Java で、ネイティブコードはありません。

## What this is（これは何か）

`muse-gadget-java` は、[Muse Gadgets SDK](https://github.com/facebookincubator/muse-gadget-sdk) のプロトコル部分を
純 Java で再実装したライブラリです。Muse Gadgets のプロトコル（BLE ペアリング、Noise トランスポート、
`/link-control` リンク）を実装しており、JVM や Android 上にガジェット（プロトコルのデバイス側）を書けます。
Pixel 8a の Android アプリで、ペアリング、Noise 接続、音声会話までエンドツーエンドに動作しているコードから切り出しました。

**独立した非公式のプロジェクト**で、Meta Platforms, Inc. の製品ではなく、上流 SDK と同じく Apache-2.0 でライセンスされています。
帰属と商標の詳細は [NOTICE](NOTICE) を参照してください。

## Upstream base（上流との関係）

Muse Gadgets SDK のコミット `7b302e3`（2026-11-12）、主にその `linux/` 実装からの再実装です。
SDK に対して何を変えたか、意図的な差分、その基準から上流がどれだけ進んだかの確認方法は
[NOTICE](NOTICE) にまとめています。

## Demo（デモ）

この core を使った Android ガジェットの動作デモ: [X の投稿](https://x.com/sakura_beers/status/2124302946074829072)

## Scope（範囲）

含むもの（パッケージ `dev.musegadget.core`）:

- BLE ペアリング v5 の暗号とプロトコル処理、デバイス側（`pairing`）
- Noise_XX トランスポート: ハンドシェイク、エンベロープ、フレーム分割（`noise`）
- `/link-control` のリンクフレーミングと、リクエストボディのアップロード規則（`link`）
- テキストユーティリティ: 文の分割とストリーミング分割（`text`）
- 小さな補助: HKDF、hex、base64url、JSON（`util`、`json`）

含まないもの: アプリ本体、音声処理、音声合成（VOICEVOX など）、UI、そして BLE / WebSocket の I/O 自体。
ライブラリはスレッドも I/O も持ちません。バイト列を渡し、返ってきたバイト列を送る形で使います。

## Requirements（動作要件）

- JDK 8 以降（ソースレベルは Java 8。ビルドは JDK 17 で動作確認しています）。
- 純 Java で Android 依存はないため、通常の JVM でも Android でも動きます。
- 依存は [noise-java](https://github.com/rweather/noise-java)（MIT License）のみです。同梱はしておらず、
  `build-and-test.sh` が固定コミットを `build-deps/` へ clone します。自分のプロジェクトで使うときは、
  noise-java のソースか jar を各自で追加してください。

## Host requirements（ホストの前提と責務）

ライブラリは I/O もポリシー判断も持ちません。組み込む側のアプリ（ホスト）が次を守ってください。

- **ペアリングの受付は、利用者の明示的な操作でのみ開く。** BLE のペアリングメッセージは、利用者が意図して開いた
  時間窓（たとえば「ペアリング開始」ボタン）の中だけで受け付け、完了またはタイムアウトで閉じてください。
  ペアリングを完了した相手には、設定した SDK トークンが渡ります。BLE の暗号化は通信路を守りますが、相手が誰かは
  認証しません。信頼の境界は、利用者が開いたその時間窓です。
- **ペアリング試行のレート制限。** 繰り返しの試行を絞るのはホストの責務です。新しい `pairing_client_hello` を受けると、
  進行中のペアリングセッションは破棄されます。受付の制限（時間窓）とレート制限がないと、他の相手が正規のペアリングを
  何度でも中断できてしまいます。
- **BLE メッセージは再組立てして 8,192 バイトまでに抑える。** BLE の断片をつないでメッセージにするのはホスト側の仕事で、
  組み立て後のメッセージはバッファ中も 8,192 バイトまでに抑えてください。念のため `PairingProtocol.onMessage` も、
  `PairingProtocol.MAX_MESSAGE_BYTES`（8,192）を超える生の入力を、解析する前に拒否します。
- **Noise 層が認証しないもの。** 本ライブラリは相手の static 鍵を pin しません。サーバの認証は、外側の WSS/TLS 接続と
  サービストークンに頼ります。ホストは、検証された TLS で目的のサービスのエンドポイントに接続してください。
- **プライバシー注記。** `get_device_info` はペアリング前に平文で応答し、端末識別子を含みます（上流プロトコルの挙動です）。
  BLE の届く範囲にいる誰でも読めます。
- **Wi-Fi の管理はホストが行う。** ネットワーク接続は OS またはアプリの仕事です。`provision_v2` には SSID とパスワード
  が含まれますが、`PairingProtocol.Provision` が返すのはサービスの接続資格情報（`accessToken`、`refreshToken`、
  `username`、`apiUrl`、`apiUrlV2`、`noiseHost`）だけです。Wi-Fi の項目は存在を検証したうえで破棄します。
  ホストがオンラインになったら `setNetworkReady(true)` を呼ぶと、`wifi_scan` は「Use current connection」の 1 件を返します。
- **SDK トークンを守る。** ログや画面に出さないでください。状態表示には、言語に依存しない set/unset を返す `SdkToken.status` が使えます。

### Android

ソースを Java 8 ソースレベルにしているのは、古い Android のツールチェーンや端末でも使えるようにするためです。
Android 10（API 29）以降の実機のアプリで使用して確認しています。それより古いバージョンは未検証です。

## Build & test（ビルドとテスト）

    ./build-and-test.sh

`git` と JDK が必要です（JDK が `PATH` にない場合は `JAVA_HOME` を設定）。スクリプトは noise-java を clone し、
`-source 8` で全体（ライブラリ、テスト、`examples/`）をコンパイルして、`tests/vectors/` に同梱した公式 SDK の
ペアリングテストベクタを含む全テストとサンプルを実行します。最後に `CORE: ALL TESTS PASSED` と出れば成功です。

## How to use（使い方）

主なクラス:

| パッケージ | クラス | 役割 |
|---|---|---|
| `pairing` | `PairingSession` | BLE ペアリング v5 の 1 セッション（デバイス側）: hello、鍵合意、暗号化レコード |
| `pairing` | `PairingProtocol` | セッション上のメッセージ振り分け。送るべきものと、`Provision`（認証情報）を返す |
| `pairing` | `SdkToken` | SDK トークンの書式チェックと、安全な set/unset 状態 |
| `noise` | `NoiseHandshake` | `Noise_XX_25519_AESGCM_SHA256` のハンドシェイク。`initiator()` がガジェット側 |
| `noise` | `NoiseTransport` | リクエストの暗号化、サーバーフレームの復号、チャンク処理 |
| `link` | `LinkFraming` | `/link-control` のフレーミング: 長さ前置の JSON メッセージ、keepalive |
| `link` | `Upload` | リクエストボディのサイズ上限と 16 KiB 分割 |
| `text` | `Sentences`, `SentenceStream` | 全文／ストリーム差分に対する文分割 |

流れは、ペアリング → Noise 接続 → リンク、の順です。最小の利用イメージ（I/O とエラー処理は省略）:

```java
import dev.musegadget.core.pairing.*;
import dev.musegadget.core.noise.*;
import dev.musegadget.core.link.LinkFraming;

// 1. BLE ペアリング: 組み立て済みの app→device メッセージを渡し、返ってきたものを送る
PairingSession session = PairingSession.create(nodeId, deviceId, mac, firmwareVersion, sdkToken);
PairingProtocol pairing = new PairingProtocol(session, nodeId, firmwareVersion);

PairingProtocol.Output out = pairing.onMessage(bleMessage);   // byte[]
for (String reply : out.send) { /* reply を BLE キャラクタリスティックへ書く */ }
if (out.provision != null) { /* out.provision.accessToken, .noiseHost など */ }
if (out.disconnect) { /* 送信の少し後に BLE リンクを切る */ }
// リンクが切れたら pairing.onDisconnect()

// 2. WebSocket 上の Noise_XX（バイナリフレーム 1 つ = Noise メッセージ 1 つ）
NoiseHandshake hs = NoiseHandshake.initiator();
byte[] msg1 = hs.writeMessage1();      // 送信
hs.readMessage2(receivedMsg2);         // 受信
byte[] msg3 = hs.writeMessage3();      // 送信
NoiseTransport noise = hs.split();

// 3. /link-control: ストリームを開き、長さ前置の JSON メッセージを送る
NoiseTransport.Encrypted stream = noise.openStream("POST", "/link-control", null);
// stream.frames を送信してから:
byte[] framed = LinkFraming.encode("{\"type\":\"...\"}");
for (byte[] frame : noise.bodyChunk(stream.streamId, framed, false)) { /* frame を送信 */ }

LinkFraming.Decoder decoder = new LinkFraming.Decoder();
Envelope.ServiceFrame f = noise.decryptFrame(receivedWsFrame); // 1 メッセージ分そろうまで null
if (f != null && f.bodyChunk != null) {
    for (Map<String, Object> message : decoder.feed(f.bodyChunk.data)) { /* 処理 */ }
}
```

[`examples/MinimalExample.java`](examples/MinimalExample.java) は、コンパイルできる完全なプログラム（ネットワーク不要）で、
Noise ハンドシェイクを行い、`/link-control` のメッセージを双方向に 1 つずつやり取りします。各部分の動く使用例は
`tests/` にもあります（たとえば `NoiseLoopbackTest`、`LinkFramingTest`）。

## Service note（サービス利用の注意）

Muse Gadgets サービスを使うには、各自の SDK トークンが必要です。
<https://gadgets.muse.ai/settings/sdk-tokens> で取得でき、サービスの利用規約に従います。
このライブラリ自体は、トークンや認証情報を含まず、提供もしません。

## Status（状況）

Pixel 8a 上の Android アプリでエンドツーエンドに動作を確認したコードから切り出したものです（想定する基準は
Android 10 / API 29 以降。上の Host requirements を参照）。このリポジトリに含まれるのはプロトコルコア
だけで、JAR などのリリース成果物はまだ公開していません。API は変更される可能性があります。

## License（ライセンス）

Apache License 2.0 です。[LICENSE](LICENSE) と [NOTICE](NOTICE) を参照してください。noise-java は MIT ライセンスで、
ビルド時に取得するもので、同梱はしていません。
