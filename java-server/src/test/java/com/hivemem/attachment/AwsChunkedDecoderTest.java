package com.hivemem.attachment;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class AwsChunkedDecoderTest {

    private static byte[] concat(byte[]... parts) {
        int n = 0;
        for (byte[] p : parts) n += p.length;
        byte[] out = new byte[n];
        int i = 0;
        for (byte[] p : parts) { System.arraycopy(p, 0, out, i, p.length); i += p.length; }
        return out;
    }

    private static byte[] ascii(String s) { return s.getBytes(StandardCharsets.US_ASCII); }

    /** Wrap payload in a single signed aws-chunked frame with a trailer, exactly like
     *  what SeaweedFS stored (size;chunk-signature=… CRLF data CRLF 0;chunk-signature=… CRLF trailer CRLF CRLF). */
    private static byte[] frameSignedWithTrailer(byte[] payload) {
        String sig = "0".repeat(64);
        return concat(
                ascii(Integer.toHexString(payload.length) + ";chunk-signature=" + sig + "\r\n"),
                payload,
                ascii("\r\n"),
                ascii("0;chunk-signature=" + sig + "\r\n"),
                ascii("x-amz-checksum-crc32:abcd==\r\n"),
                ascii("x-amz-trailer-signature:" + sig + "\r\n"),
                ascii("\r\n"));
    }

    /** Multi-chunk signed frame (no trailer): two data chunks + final zero chunk. */
    private static byte[] frameSignedMultiChunk(byte[] c1, byte[] c2) {
        String sig = "0".repeat(64);
        return concat(
                ascii(Integer.toHexString(c1.length) + ";chunk-signature=" + sig + "\r\n"),
                c1, ascii("\r\n"),
                ascii(Integer.toHexString(c2.length) + ";chunk-signature=" + sig + "\r\n"),
                c2, ascii("\r\n"),
                ascii("0;chunk-signature=" + sig + "\r\n\r\n"));
    }

    @Test
    void detectsFramedPayload() {
        byte[] jpeg = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 1, 2, 3};
        assertThat(AwsChunkedDecoder.isChunked(frameSignedWithTrailer(jpeg))).isTrue();
    }

    @Test
    void plainBytesAreNotDetectedAsFramed() {
        byte[] jpeg = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0, 0x10, 'J', 'F', 'I', 'F'};
        assertThat(AwsChunkedDecoder.isChunked(jpeg)).isFalse();
        // and decode is a no-op for already-clean bytes
        assertThat(AwsChunkedDecoder.decode(jpeg)).isEqualTo(jpeg);
    }

    @Test
    void stripsSingleChunkWithTrailerLosslessly() {
        byte[] payload = new byte[37529];
        for (int i = 0; i < payload.length; i++) payload[i] = (byte) (i % 251);
        payload[0] = (byte) 0xFF; payload[1] = (byte) 0xD8; payload[2] = (byte) 0xFF;
        byte[] framed = frameSignedWithTrailer(payload);
        assertThat(framed.length).isGreaterThan(payload.length); // framing added bytes
        assertThat(AwsChunkedDecoder.decode(framed)).isEqualTo(payload);
    }

    @Test
    void stripsMultipleChunksLosslessly() {
        byte[] c1 = new byte[65536];
        byte[] c2 = new byte[1234];
        Arrays.fill(c1, (byte) 0xAB);
        Arrays.fill(c2, (byte) 0xCD);
        byte[] expected = concat(c1, c2);
        byte[] framed = frameSignedMultiChunk(c1, c2);
        assertThat(AwsChunkedDecoder.decode(framed)).isEqualTo(expected);
    }

    @Test
    void emptyInputReturnsEmpty() {
        assertThat(AwsChunkedDecoder.decode(new byte[0])).isEmpty();
    }
}
