package com.hivemem.attachment;

import java.io.ByteArrayOutputStream;

/**
 * Recovers the true object bytes from an S3 {@code aws-chunked} (SigV4 streaming)
 * body that was stored verbatim.
 *
 * <p>SeaweedFS does not decode SigV4 streaming request bodies, so attachments
 * uploaded with the AWS SDK's default chunked signing were persisted with the
 * chunk framing baked in:
 * <pre>
 *   &lt;hex-size&gt;;chunk-signature=&lt;64 hex&gt;\r\n &lt;size bytes&gt; \r\n
 *   ...
 *   0;chunk-signature=&lt;64 hex&gt;\r\n   (optional trailer headers)  \r\n
 * </pre>
 * That framing corrupts the JPEG/PDF so it cannot be decoded. Stripping it is
 * deterministic and lossless. New uploads no longer need this (chunked encoding
 * is disabled on the client); this is for repairing already-stored objects.
 */
public final class AwsChunkedDecoder {

    private AwsChunkedDecoder() {}

    /**
     * True if {@code data} begins with an aws-chunked chunk header
     * ({@code <hex>;chunk-signature=}). Plain object bytes never start this way.
     */
    public static boolean isChunked(byte[] data) {
        int semi = indexOf(data, (byte) ';', 0, Math.min(data.length, 32));
        if (semi <= 0) return false;
        for (int i = 0; i < semi; i++) {
            if (Character.digit(data[i] & 0xFF, 16) < 0) return false; // must be hex size
        }
        // require the ";chunk-signature=" marker right after the size
        return startsWith(data, semi, ";chunk-signature=");
    }

    /** Returns the de-framed bytes, or {@code data} unchanged when it is not chunked. */
    public static byte[] decode(byte[] data) {
        if (!isChunked(data)) return data;
        ByteArrayOutputStream out = new ByteArrayOutputStream(data.length);
        int pos = 0;
        while (pos < data.length) {
            int eol = indexOf(data, (byte) '\r', pos, data.length);
            if (eol < 0 || eol + 1 >= data.length || data[eol + 1] != '\n') break;
            // chunk-size is the hex number before an optional ';' extension
            int semi = indexOf(data, (byte) ';', pos, eol);
            int sizeEnd = (semi >= 0 && semi < eol) ? semi : eol;
            long size = parseHex(data, pos, sizeEnd);
            int dataStart = eol + 2; // skip CRLF after the header line
            if (size == 0) break;    // final chunk — trailer/garbage follows, discard
            if (dataStart + size > data.length) {
                // truncated/garbled — salvage what is present and stop
                out.write(data, dataStart, data.length - dataStart);
                break;
            }
            out.write(data, dataStart, (int) size);
            pos = (int) (dataStart + size + 2); // skip chunk data + its trailing CRLF
        }
        return out.toByteArray();
    }

    private static long parseHex(byte[] data, int from, int to) {
        long v = 0;
        for (int i = from; i < to; i++) {
            int d = Character.digit(data[i] & 0xFF, 16);
            if (d < 0) break;
            v = (v << 4) | d;
        }
        return v;
    }

    private static int indexOf(byte[] data, byte target, int from, int to) {
        for (int i = from; i < to && i < data.length; i++) {
            if (data[i] == target) return i;
        }
        return -1;
    }

    private static boolean startsWith(byte[] data, int offset, String marker) {
        if (offset + marker.length() > data.length) return false;
        for (int i = 0; i < marker.length(); i++) {
            if ((data[offset + i] & 0xFF) != marker.charAt(i)) return false;
        }
        return true;
    }
}
