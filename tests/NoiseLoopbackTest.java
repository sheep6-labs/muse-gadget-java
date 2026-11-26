import dev.musegadget.core.noise.Envelope;
import dev.musegadget.core.noise.Envelope.ApplicationResponse;
import dev.musegadget.core.noise.Envelope.Header;
import dev.musegadget.core.noise.Envelope.Kind;
import dev.musegadget.core.noise.Envelope.ServiceFrame;
import dev.musegadget.core.noise.NoiseFrames;
import dev.musegadget.core.noise.NoiseHandshake;
import dev.musegadget.core.noise.NoiseTransport;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/** Java initiator <-> Java responder, Noise_XX_25519_AESGCM_SHA256, 200 KB through chunking + envelope. */
public final class NoiseLoopbackTest {
    /** {gadget, vm} transports from a fresh loopback handshake. */
    static boolean acceptsService(int service) {
        try {
            return Envelope.decodeServiceRequest(Envelope.encodeServiceRequest(service, new byte[] {1})).service == service;
        } catch (RuntimeException e) {
            return false;
        }
    }

    static NoiseTransport[] pair() throws Exception {
        NoiseHandshake i = NoiseHandshake.initiator(), r = NoiseHandshake.responder();
        r.readMessage1(i.writeMessage1());
        i.readMessage2(r.writeMessage2(new byte[0]));
        r.readMessage3(i.writeMessage3());
        return new NoiseTransport[] {i.split(), r.split()};
    }

    public static void main(String[] args) throws Exception {
        NoiseHandshake ini = NoiseHandshake.initiator();
        NoiseHandshake res = NoiseHandshake.responder();
        byte[] m1 = ini.writeMessage1();
        T.check("msg1 is 32 bytes (ephemeral key only)", m1.length == 32, "len=" + m1.length);
        res.readMessage1(m1);
        byte[] m2 = res.writeMessage2(new byte[0]);
        T.check("msg2 is 96 bytes (e + enc(s) + tag)", m2.length == 32 + 48 + 16, "len=" + m2.length);
        ini.readMessage2(m2);
        byte[] m3 = ini.writeMessage3();
        T.check("msg3 is 64 bytes (enc(s) + empty payload tag)", m3.length == 48 + 16, "len=" + m3.length);
        byte[] p3 = res.readMessage3(m3);
        T.check("msg3 payload empty", p3.length == 0);
        T.check("both sides complete", ini.complete() && res.complete());
        NoiseTransport gadget = ini.split();
        NoiseTransport vm = res.split();

        // 200 KB request body: gadget -> VM
        byte[] body = new byte[200 * 1024];
        new Random(42).nextBytes(body);
        List<Header> hdrs = Arrays.asList(new Header("content-type", "application/json"), new Header("x-app-id", "musegadget"));
        NoiseTransport.Encrypted enc = gadget.request("POST", "/chat/stream", body, hdrs);
        int expectChunks = (int) Math.ceil((body.length + 100) / (double) NoiseFrames.MAX_CHUNK_PAYLOAD);
        T.check("stream id starts at 1", enc.streamId == 1);
        T.check("200KB is split into multiple chunks", enc.frames.size() >= 4, "frames=" + enc.frames.size() + " (~" + expectChunks + ")");
        boolean sizesOk = true;
        for (byte[] f : enc.frames) sizesOk &= f.length <= 65535;
        T.check("every Noise message <= 65535 bytes", sizesOk);

        // deliver out of nothing special: in order, first frames return null
        ServiceFrame got = null;
        for (int i = 0; i < enc.frames.size(); i++) {
            ServiceFrame f = vm.peerDecryptFrame(enc.frames.get(i));
            if (i < enc.frames.size() - 1) T.check("partial chunk " + i + " -> null", f == null);
            else got = f;
        }
        T.check("reassembled request frame", got != null && got.kind == Kind.REQUEST && got.streamId == 1);
        T.check("request verb/path/headers", got != null && "POST".equals(got.request.verb) && "/chat/stream".equals(got.request.path)
                && got.request.headers.size() == 2 && "x-app-id".equals(got.request.headers.get(1).key)
                && "musegadget".equals(got.request.headers.get(1).value) && got.request.endBody);
        T.check("200KB body round-trips byte-exact (gadget -> VM)", got != null && Arrays.equals(body, got.request.body));

        // 200 KB response body: VM -> gadget, as a body_chunk
        byte[] reply = new byte[200 * 1024];
        new Random(7).nextBytes(reply);
        List<byte[]> back = vm.peerEncrypt(ServiceFrame.bodyChunk(1, new Envelope.BodyChunk(reply, true)));
        ServiceFrame r = null;
        for (byte[] f : back) r = gadget.decryptFrame(f);
        T.check("200KB body round-trips byte-exact (VM -> gadget)", r != null && r.kind == Kind.BODY_CHUNK && r.streamId == 1
                && Arrays.equals(reply, r.bodyChunk.data) && r.bodyChunk.endBody);

        // response + reset + second stream id
        ApplicationResponse ar = NoiseTransport.okResponse();
        ar.headers.add(new Header("content-type", "application/x-ndjson"));
        ar.endBody = false;
        ServiceFrame rr = gadget.decryptFrame(vm.peerEncrypt(ServiceFrame.response(2, ar)).get(0));
        T.check("response frame (status 200, header)", rr != null && rr.kind == Kind.RESPONSE && rr.response.status == 200
                && rr.response.headers.get(0).value.equals("application/x-ndjson"));
        NoiseTransport.Encrypted s2 = gadget.openStream("POST", "/link-control", null);
        T.check("second stream id is 2", s2.streamId == 2);
        ServiceFrame open = vm.peerDecryptFrame(s2.frames.get(0));
        T.check("streaming request has end_body=false", open != null && !open.request.endBody && "/link-control".equals(open.request.path));
        ServiceFrame rst = gadget.decryptFrame(vm.peerEncrypt(ServiceFrame.reset(2, new Envelope.Reset(Envelope.Reset.CANCELLED, "bye"))).get(0));
        T.check("reset frame (cancelled, reason)", rst != null && rst.kind == Kind.RESET && rst.reset.code == 1 && "bye".equals(rst.reset.reason));

        T.check("daemon streams decode as service 0", open.service == Envelope.SERVICE_DAEMON);

        // service-6 stream: request, body chunks and reset all carry the stream's service
        NoiseTransport.Encrypted g = gadget.openStream("POST", "/api/gadget/control", null, Envelope.SERVICE_GADGET);
        ServiceFrame gOpen = vm.peerDecryptFrame(g.frames.get(0));
        T.check("service-6 stream request decoded", gOpen != null && gOpen.kind == Kind.REQUEST && gOpen.streamId == g.streamId
                && "/api/gadget/control".equals(gOpen.request.path) && !gOpen.request.endBody);
        T.check("service-6 request carries service 6", gOpen.service == Envelope.SERVICE_GADGET);
        ServiceFrame gChunk = vm.peerDecryptFrame(gadget.bodyChunk(g.streamId, new byte[] {1, 2, 3}, false).get(0));
        T.check("service-6 body chunk carries service 6", gChunk != null && gChunk.kind == Kind.BODY_CHUNK
                && gChunk.service == Envelope.SERVICE_GADGET && Arrays.equals(new byte[] {1, 2, 3}, gChunk.bodyChunk.data));
        ServiceFrame gReply = gadget.decryptFrame(vm.peerEncrypt(ServiceFrame.bodyChunk(g.streamId, new Envelope.BodyChunk(new byte[] {9}, false))).get(0));
        T.check("service-6 stream receives replies", gReply != null && gReply.kind == Kind.BODY_CHUNK && gReply.streamId == g.streamId);
        ServiceFrame gReset = vm.peerDecryptFrame(gadget.reset(g.streamId, Envelope.Reset.CANCELLED, "x").get(0));
        T.check("service-6 reset carries service 6", gReset != null && gReset.kind == Kind.RESET && gReset.service == Envelope.SERVICE_GADGET);
        NoiseTransport.Encrypted gOne = gadget.request("GET", "/api/gadget/me", new byte[0], null, Envelope.SERVICE_GADGET);
        T.check("complete service-6 request", vm.peerDecryptFrame(gOne.frames.get(0)).service == Envelope.SERVICE_GADGET);
        T.check("chat stream stays on service 0", vm.peerDecryptFrame(gadget.request("POST", "/chat/stream", new byte[0], null).frames.get(0)).service == Envelope.SERVICE_DAEMON);
        boolean badSvc = false;
        try { gadget.openStream("POST", "/x", null, 4); } catch (IllegalArgumentException e) { badSvc = true; }
        T.check("unknown service refused when opening", badSvc);
        T.check("decoder accepts services 0-3 and 6", acceptsService(0) && acceptsService(1) && acceptsService(2) && acceptsService(3) && acceptsService(6));
        T.check("decoder rejects services 4, 5 and 7", !acceptsService(4) && !acceptsService(5) && !acceptsService(7));

        // empty body_chunk is allowed (end marker only)
        ServiceFrame end = vm.peerDecryptFrame(gadget.bodyChunk(2, new byte[0], true).get(0));
        T.check("empty end chunk", end != null && end.kind == Kind.BODY_CHUNK && end.bodyChunk.endBody && end.bodyChunk.data.length == 0);

        // tampering kills the transport
        List<byte[]> t = vm.peerEncrypt(ServiceFrame.reset(1, new Envelope.Reset(1, "")));
        byte[] bad = t.get(0).clone();
        bad[bad.length - 1] ^= 1;
        boolean threw = false;
        try { gadget.decryptFrame(bad); } catch (RuntimeException e) { threw = true; }
        T.check("tampered frame rejected", threw);
        threw = false;
        try { gadget.request("GET", "/x", new byte[0], null); } catch (IllegalStateException e) { threw = true; }
        T.check("transport dead after failure", threw);

        // NoiseFrames: protocol limits
        T.check("max chunk payload = 65489", NoiseFrames.MAX_CHUNK_PAYLOAD == 65489);
        List<byte[]> one = NoiseFrames.encode(new byte[NoiseFrames.MAX_CHUNK_PAYLOAD], 5);
        T.check("exactly 65489 bytes -> 1 chunk", one.size() == 1);
        List<byte[]> two = NoiseFrames.encode(new byte[NoiseFrames.MAX_CHUNK_PAYLOAD + 1], 5);
        T.check("65490 bytes -> 2 chunks", two.size() == 2);
        T.check("empty payload -> 1 empty chunk", NoiseFrames.encode(new byte[0], 5).size() == 1);
        boolean rejected = false;
        try { NoiseFrames.encode(new byte[NoiseFrames.MAX_CHUNK_PAYLOAD * 256 + 1], 1); } catch (IllegalArgumentException e) { rejected = true; }
        T.check("more than 256 chunks rejected", rejected);

        // out-of-order delivery reassembles; duplicate index poisons
        byte[] data = new byte[150000];
        new Random(1).nextBytes(data);
        List<byte[]> frames = NoiseFrames.encode(data, -77L); // negative chunk id exercises 10-byte varint
        List<byte[]> shuffled = new ArrayList<byte[]>(frames);
        Collections.reverse(shuffled);
        NoiseFrames.Decoder d = new NoiseFrames.Decoder();
        byte[] out = null;
        for (byte[] f : shuffled) out = d.decode(f);
        T.check("out-of-order chunks reassemble (negative chunk_id)", out != null && Arrays.equals(data, out));
        d = new NoiseFrames.Decoder();
        d.decode(frames.get(0));
        threw = false;
        try { d.decode(frames.get(0)); } catch (IllegalArgumentException e) { threw = true; }
        T.check("duplicate chunk index rejected", threw);
        threw = false;
        try { d.decode(frames.get(1)); } catch (IllegalStateException e) { threw = true; }
        T.check("decoder poisoned after failure", threw);

        // total budget across in-flight assemblies: many half-finished large messages are refused
        d = new NoiseFrames.Decoder();
        int perAssembly = NoiseFrames.MAX_PENDING_BYTES / NoiseFrames.MAX_CHUNK_PAYLOAD / NoiseFrames.MAX_PENDING_ASSEMBLIES + 2;
        byte[] chunk = new byte[NoiseFrames.MAX_CHUNK_PAYLOAD];
        byte[] spread = new byte[NoiseFrames.MAX_CHUNK_PAYLOAD * NoiseFrames.MAX_TOTAL_CHUNKS];
        List<List<byte[]>> parts = new ArrayList<List<byte[]>>();
        for (int id = 1; id <= NoiseFrames.MAX_PENDING_ASSEMBLIES; id++) parts.add(NoiseFrames.encode(spread, id));
        threw = false;
        int accepted = 0;
        try {
            for (int i = 0; i < perAssembly && !threw; i++)
                for (int id = 0; id < NoiseFrames.MAX_PENDING_ASSEMBLIES; id++) { d.decode(parts.get(id).get(i)); accepted++; }
        } catch (IllegalArgumentException e) { threw = true; }
        T.check("total pending bytes capped across assemblies", threw && (long) accepted * chunk.length <= NoiseFrames.MAX_PENDING_BYTES,
                "accepted=" + accepted);
        threw = false;
        try { d.decode(parts.get(0).get(0)); } catch (IllegalStateException e) { threw = true; }
        T.check("decoder poisoned after budget failure", threw);
        d = new NoiseFrames.Decoder();
        byte[] after = null;
        for (byte[] f : frames) after = d.decode(f);
        T.check("fresh decoder unaffected", after != null && Arrays.equals(data, after));

        // oversized Noise message is refused before any decryption
        NoiseTransport g2 = pair()[0];
        String msg = null;
        try { g2.decryptFrame(new byte[NoiseTransport.MAX_NOISE_MESSAGE + 1]); } catch (IllegalStateException e) { msg = e.getMessage(); }
        T.check("noise message > 65535 bytes rejected as too large", msg != null && msg.contains("too large"), String.valueOf(msg));
        threw = false;
        try { g2.request("GET", "/x", new byte[0], null); } catch (IllegalStateException e) { threw = true; }
        T.check("transport dead after oversized message", threw);
        NoiseTransport g3 = pair()[0];
        msg = null;
        try { g3.decryptFrame(new byte[NoiseTransport.MAX_NOISE_MESSAGE]); } catch (IllegalStateException e) { msg = e.getMessage(); }
        T.check("noise message of exactly 65535 bytes passes the size check", msg != null && !msg.contains("too large"), String.valueOf(msg));
        T.done();
    }
}
