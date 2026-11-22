/*
 * Derived from the Muse Gadgets SDK (https://github.com/facebookincubator/muse-gadget-sdk),
 * Copyright (c) Meta Platforms, Inc. and affiliates, licensed under the Apache License, Version 2.0.
 * This file re-implements the SDK's Noise transport behaviour in Java (not a verbatim copy); see NOTICE.
 */
package dev.musegadget.core.noise;

import dev.musegadget.core.noise.Proto.In;
import dev.musegadget.core.noise.Proto.Out;
import dev.musegadget.core.noise.Proto.ProtoException;

import java.util.ArrayList;
import java.util.List;

/**
 * Hand-written protobuf envelope, matching SDK noise/envelope.py:
 * ServiceRequest / ServiceResponse wrapping a ServiceFrame (request | response | body_chunk | reset).
 * Both directions are implemented so tests can play the VM side.
 */
public final class Envelope {
    private Envelope() {}

    public static final int SERVICE_DAEMON = 0;

    public static final class Header {
        public final String key, value;
        public Header(String key, String value) { this.key = key; this.value = value; }
    }

    public static final class ApplicationRequest {
        public String verb = "", path = "";
        public List<Header> headers = new ArrayList<Header>();
        public byte[] body = new byte[0];
        public boolean endBody;
    }

    public static final class ApplicationResponse {
        public int status;
        public List<Header> headers = new ArrayList<Header>();
        public byte[] body = new byte[0];
        public boolean endBody;
    }

    public static final class BodyChunk {
        public byte[] data = new byte[0];
        public boolean endBody;
        public BodyChunk() {}
        public BodyChunk(byte[] data, boolean endBody) { this.data = data; this.endBody = endBody; }
    }

    public static final class Reset {
        public static final int CANCELLED = 1, TIMEOUT = 2, PROTOCOL_ERROR = 3, REFUSED_STREAM = 4,
                INTERNAL_ERROR = 5, SERVICE_UNAVAILABLE = 6;
        public int code;
        public String reason = "";
        public Reset() {}
        public Reset(int code, String reason) { this.code = code; this.reason = reason; }
    }

    public enum Kind { NONE, REQUEST, RESPONSE, BODY_CHUNK, RESET }

    /** ServiceFrame: stream id plus exactly one of the four payload kinds. */
    public static final class ServiceFrame {
        public long streamId;
        public Kind kind = Kind.NONE;
        public ApplicationRequest request;
        public ApplicationResponse response;
        public BodyChunk bodyChunk;
        public Reset reset;

        public static ServiceFrame request(long id, ApplicationRequest r) {
            ServiceFrame f = new ServiceFrame(); f.streamId = id; f.kind = Kind.REQUEST; f.request = r; return f;
        }
        public static ServiceFrame response(long id, ApplicationResponse r) {
            ServiceFrame f = new ServiceFrame(); f.streamId = id; f.kind = Kind.RESPONSE; f.response = r; return f;
        }
        public static ServiceFrame bodyChunk(long id, BodyChunk c) {
            ServiceFrame f = new ServiceFrame(); f.streamId = id; f.kind = Kind.BODY_CHUNK; f.bodyChunk = c; return f;
        }
        public static ServiceFrame reset(long id, Reset r) {
            ServiceFrame f = new ServiceFrame(); f.streamId = id; f.kind = Kind.RESET; f.reset = r; return f;
        }
    }

    // ---- headers ----

    private static byte[] encHeader(Header h) {
        Out o = new Out();
        if (!h.key.isEmpty()) o.stringField(1, h.key);
        if (!h.value.isEmpty()) o.stringField(2, h.value);
        return o.toBytes();
    }

    private static Header decHeader(byte[] data) {
        In in = new In(data);
        String k = "", v = "";
        while (in.more()) {
            in.next();
            if (in.field == 1) { in.expect(Proto.WIRE_DELIMITED, "Header.key"); k = in.string(); }
            else if (in.field == 2) { in.expect(Proto.WIRE_DELIMITED, "Header.value"); v = in.string(); }
            else in.skip();
        }
        return new Header(k, v);
    }

    // ---- application request / response ----

    static byte[] encode(ApplicationRequest r) {
        Out o = new Out();
        if (!r.verb.isEmpty()) o.stringField(1, r.verb);
        if (!r.path.isEmpty()) o.stringField(2, r.path);
        for (Header h : r.headers) o.bytesField(3, encHeader(h));
        if (r.body.length > 0) o.bytesField(4, r.body);
        if (r.endBody) o.boolField(5, true);
        return o.toBytes();
    }

    static ApplicationRequest decodeRequest(byte[] data) {
        In in = new In(data);
        ApplicationRequest r = new ApplicationRequest();
        while (in.more()) {
            in.next();
            switch (in.field) {
                case 1: in.expect(Proto.WIRE_DELIMITED, "ApplicationRequest.verb"); r.verb = in.string(); break;
                case 2: in.expect(Proto.WIRE_DELIMITED, "ApplicationRequest.path"); r.path = in.string(); break;
                case 3: in.expect(Proto.WIRE_DELIMITED, "ApplicationRequest.headers"); r.headers.add(decHeader(in.delimited())); break;
                case 4: in.expect(Proto.WIRE_DELIMITED, "ApplicationRequest.body"); r.body = in.delimited(); break;
                case 5: in.expect(Proto.WIRE_VARINT, "ApplicationRequest.end_body"); r.endBody = in.varint() != 0; break;
                default: in.skip();
            }
        }
        return r;
    }

    static byte[] encode(ApplicationResponse r) {
        Out o = new Out();
        if (r.status != 0) o.int32Field(1, r.status);
        for (Header h : r.headers) o.bytesField(2, encHeader(h));
        if (r.body.length > 0) o.bytesField(3, r.body);
        if (r.endBody) o.boolField(4, true);
        return o.toBytes();
    }

    static ApplicationResponse decodeResponse(byte[] data) {
        In in = new In(data);
        ApplicationResponse r = new ApplicationResponse();
        while (in.more()) {
            in.next();
            switch (in.field) {
                case 1: in.expect(Proto.WIRE_VARINT, "ApplicationResponse.status"); r.status = In.int32(in.varint()); break;
                case 2: in.expect(Proto.WIRE_DELIMITED, "ApplicationResponse.headers"); r.headers.add(decHeader(in.delimited())); break;
                case 3: in.expect(Proto.WIRE_DELIMITED, "ApplicationResponse.body"); r.body = in.delimited(); break;
                case 4: in.expect(Proto.WIRE_VARINT, "ApplicationResponse.end_body"); r.endBody = in.varint() != 0; break;
                default: in.skip();
            }
        }
        return r;
    }

    static byte[] encode(BodyChunk c) {
        Out o = new Out();
        if (c.data.length > 0) o.bytesField(1, c.data);
        if (c.endBody) o.boolField(2, true);
        return o.toBytes();
    }

    static BodyChunk decodeBodyChunk(byte[] data) {
        In in = new In(data);
        BodyChunk c = new BodyChunk();
        while (in.more()) {
            in.next();
            if (in.field == 1) { in.expect(Proto.WIRE_DELIMITED, "BodyChunk.data"); c.data = in.delimited(); }
            else if (in.field == 2) { in.expect(Proto.WIRE_VARINT, "BodyChunk.end_body"); c.endBody = in.varint() != 0; }
            else in.skip();
        }
        return c;
    }

    static byte[] encode(Reset r) {
        Out o = new Out();
        if (r.code != 0) o.int32Field(1, r.code);
        if (!r.reason.isEmpty()) o.stringField(2, r.reason);
        return o.toBytes();
    }

    static Reset decodeReset(byte[] data) {
        In in = new In(data);
        Reset r = new Reset();
        while (in.more()) {
            in.next();
            if (in.field == 1) {
                in.expect(Proto.WIRE_VARINT, "Reset.code");
                r.code = In.int32(in.varint());
                if (r.code < 0 || r.code > 6) throw new ProtoException("unknown reset code");
            } else if (in.field == 2) { in.expect(Proto.WIRE_DELIMITED, "Reset.reason"); r.reason = in.string(); }
            else in.skip();
        }
        return r;
    }

    // ---- service frame ----

    public static byte[] encodeFrame(ServiceFrame f) {
        Out o = new Out();
        if (f.streamId != 0) o.varintField(1, f.streamId);
        switch (f.kind) {
            case NONE: break;
            case REQUEST: o.bytesField(2, encode(f.request)); break;
            case RESPONSE: o.bytesField(3, encode(f.response)); break;
            case BODY_CHUNK: o.bytesField(4, encode(f.bodyChunk)); break;
            case RESET: o.bytesField(5, encode(f.reset)); break;
        }
        return o.toBytes();
    }

    public static ServiceFrame decodeFrame(byte[] data) {
        In in = new In(data);
        ServiceFrame f = new ServiceFrame();
        while (in.more()) {
            in.next();
            switch (in.field) {
                case 1: in.expect(Proto.WIRE_VARINT, "ServiceFrame.stream_id"); f.streamId = in.varint(); break;
                case 2: in.expect(Proto.WIRE_DELIMITED, "ServiceFrame.request"); f.kind = Kind.REQUEST; f.request = decodeRequest(in.delimited()); break;
                case 3: in.expect(Proto.WIRE_DELIMITED, "ServiceFrame.response"); f.kind = Kind.RESPONSE; f.response = decodeResponse(in.delimited()); break;
                case 4: in.expect(Proto.WIRE_DELIMITED, "ServiceFrame.body_chunk"); f.kind = Kind.BODY_CHUNK; f.bodyChunk = decodeBodyChunk(in.delimited()); break;
                case 5: in.expect(Proto.WIRE_DELIMITED, "ServiceFrame.reset"); f.kind = Kind.RESET; f.reset = decodeReset(in.delimited()); break;
                default: in.skip();
            }
        }
        return f;
    }

    // ---- service request / response wrappers ----

    public static final class ServiceRequest {
        public int service;
        public byte[] payload = new byte[0];
    }

    public static byte[] encodeServiceRequest(int service, byte[] payload) {
        Out o = new Out();
        if (service != SERVICE_DAEMON) o.varintField(1, service);
        if (payload.length > 0) o.bytesField(2, payload);
        return o.toBytes();
    }

    public static ServiceRequest decodeServiceRequest(byte[] data) {
        In in = new In(data);
        ServiceRequest r = new ServiceRequest();
        while (in.more()) {
            in.next();
            if (in.field == 1) {
                in.expect(Proto.WIRE_VARINT, "ServiceRequest.service");
                long v = in.varint();
                if (v < 0 || v > 3) throw new ProtoException("unknown service type");
                r.service = (int) v;
            } else if (in.field == 2) { in.expect(Proto.WIRE_DELIMITED, "ServiceRequest.payload"); r.payload = in.delimited(); }
            else in.skip();
        }
        return r;
    }

    public static byte[] encodeServiceResponse(byte[] payload) {
        if (payload.length == 0) return new byte[0];
        Out o = new Out();
        o.bytesField(1, payload);
        return o.toBytes();
    }

    public static byte[] decodeServiceResponse(byte[] data) {
        In in = new In(data);
        byte[] payload = new byte[0];
        while (in.more()) {
            in.next();
            if (in.field == 1) { in.expect(Proto.WIRE_DELIMITED, "ServiceResponse.payload"); payload = in.delimited(); }
            else in.skip();
        }
        return payload;
    }
}
