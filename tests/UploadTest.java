import dev.musegadget.core.link.Upload;

import java.util.List;

/** Chat request-size rules: 64 KiB inline limit, 16 KiB chunks, 2 MiB voice note. */
public final class UploadTest {
    public static void main(String[] args) {
        T.check("size: 64 KiB is inline, 64 KiB + 1 is chunked", !Upload.needsChunking(new byte[64 * 1024]) && Upload.needsChunking(new byte[64 * 1024 + 1]));
        byte[] body = new byte[40000];
        for (int i = 0; i < body.length; i++) body[i] = (byte) i;
        List<byte[]> parts = Upload.chunks(body);
        T.check("chunks: 16 KiB, 16 KiB, rest", parts.size() == 3 && parts.get(0).length == 16384 && parts.get(1).length == 16384 && parts.get(2).length == 40000 - 32768);
        int pos = 0; boolean same = true;
        for (byte[] p : parts) for (byte b : p) same &= b == body[pos++];
        T.check("chunks concatenate to the body", same && pos == body.length);
        T.check("chunks: exact multiple has no empty tail", Upload.chunks(new byte[32768]).size() == 2);
        T.check("chunks: empty body -> none", Upload.chunks(new byte[0]).isEmpty());
        Upload.checkVoice(new byte[1]);
        Upload.checkVoice(new byte[Upload.MAX_VOICE_BYTES]);
        T.check("voice: 2 MiB ok, 2 MiB + 1 / empty / null rejected", throwsIae(new byte[Upload.MAX_VOICE_BYTES + 1]) && throwsIae(new byte[0]) && throwsIae(null));
        T.done();
    }

    static boolean throwsIae(byte[] b) {
        try { Upload.checkVoice(b); return false; } catch (IllegalArgumentException e) { return true; }
    }
}
