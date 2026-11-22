import dev.musegadget.core.link.LinkFraming;
import dev.musegadget.core.noise.Envelope.ServiceFrame;
import dev.musegadget.core.noise.NoiseHandshake;
import dev.musegadget.core.noise.NoiseTransport;

import java.util.List;
import java.util.Map;

/**
 * Minimal end-to-end sketch with no network: a Noise_XX handshake between the gadget (initiator) and a
 * loopback peer standing in for the server, then one /link-control message each way.
 * In a real gadget the frames are written to / read from your WebSocket (one binary frame = one Noise message).
 */
public final class MinimalExample {
    public static void main(String[] args) throws Exception {
        // Handshake: msg1 (gadget -> server), msg2 (server -> gadget), msg3 (gadget -> server)
        NoiseHandshake gadgetHs = NoiseHandshake.initiator();
        NoiseHandshake serverHs = NoiseHandshake.responder();
        serverHs.readMessage1(gadgetHs.writeMessage1());
        gadgetHs.readMessage2(serverHs.writeMessage2(new byte[0]));
        serverHs.readMessage3(gadgetHs.writeMessage3());
        NoiseTransport gadget = gadgetHs.split();
        NoiseTransport server = serverHs.split();

        // Gadget: open the /link-control stream, then send one length-prefixed JSON message on it
        NoiseTransport.Encrypted stream = gadget.openStream("POST", "/link-control", null);
        for (byte[] frame : stream.frames) server.peerDecryptFrame(frame); // "send" and "receive"
        byte[] hello = LinkFraming.encode("{\"type\":\"hello\"}");
        ServiceFrame atServer = null;
        for (byte[] frame : gadget.bodyChunk(stream.streamId, hello, false)) atServer = server.peerDecryptFrame(frame);
        List<Map<String, Object>> received = new LinkFraming.Decoder().feed(atServer.bodyChunk.data);
        System.out.println("server received: " + received.get(0));

        // Server -> gadget: one reply message
        byte[] reply = LinkFraming.encode("{\"type\":\"welcome\"}");
        ServiceFrame atGadget = null;
        for (byte[] frame : server.peerEncrypt(ServiceFrame.bodyChunk(stream.streamId,
                new dev.musegadget.core.noise.Envelope.BodyChunk(reply, false)))) {
            atGadget = gadget.decryptFrame(frame); // null until the whole message has arrived
        }
        for (Map<String, Object> message : new LinkFraming.Decoder().feed(atGadget.bodyChunk.data)) {
            System.out.println("gadget received: " + message);
        }
    }
}
