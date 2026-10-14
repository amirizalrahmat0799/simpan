package io.github.simpan.io;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.simpan.api.S3Error;
import io.github.simpan.api.S3Exception;
import io.github.simpan.auth.AuthResult;
import io.github.simpan.auth.SigV4;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/** The chunked bodies from the S3 streaming-upload documentation, signature for signature. */
class AwsChunkedInputStreamTest {

    static final byte[] KEY = SigV4.signingKey("wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY", "20130524", "us-east-1", "s3");
    static final String SCOPE = "20130524/us-east-1/s3/aws4_request";

    static AuthResult auth(String mode, String seed) {
        return new AuthResult("AKIAIOSFODNN7EXAMPLE", KEY, "20130524T000000Z", SCOPE, seed, mode, false);
    }

    static byte[] a(int n) {
        byte[] b = new byte[n];
        Arrays.fill(b, (byte) 'a');
        return b;
    }

    static byte[] body(String... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (String p : parts) {
            if (p.startsWith("DATA:")) out.writeBytes(a(Integer.parseInt(p.substring(5))));
            else out.writeBytes(p.getBytes(StandardCharsets.US_ASCII));
        }
        return out.toByteArray();
    }

    static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[7000];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }

    static final byte[] SIGNED = body(
            "10000;chunk-signature=ad80c730a21e5b8d04586a2213dd63b9a0e99e0e2307b0ade35a65485a288648\r\n", "DATA:65536", "\r\n",
            "400;chunk-signature=0055627c9e194cb4542bae2aa5492e3c1575bbb81b612b7d234b86a503ef5497\r\n", "DATA:1024", "\r\n",
            "0;chunk-signature=b6c6ea8a5354eaf15b3cb7646744f4275b71ea724fed81ceb9323e279d449df9\r\n", "\r\n");

    @Test
    void signedChunksFromTheDocumentation() throws IOException {
        AwsChunkedInputStream in = new AwsChunkedInputStream(new ByteArrayInputStream(SIGNED),
                auth(SigV4.STREAMING_SIGNED, "4f232c4386841ef735655705268965c44a0e4690baa4adea153f7db9fa80a0a9"));
        assertThat(readAll(in)).isEqualTo(a(66560));
        assertThat(in.decodedBytes()).isEqualTo(66560);
    }

    @Test
    void aTamperedByteIsCaughtAtTheEndOfItsChunk() {
        byte[] tampered = SIGNED.clone();
        tampered[200] = 'b';
        AwsChunkedInputStream in = new AwsChunkedInputStream(new ByteArrayInputStream(tampered),
                auth(SigV4.STREAMING_SIGNED, "4f232c4386841ef735655705268965c44a0e4690baa4adea153f7db9fa80a0a9"));
        assertThatThrownBy(() -> readAll(in))
                .isInstanceOfSatisfying(S3Exception.class, e -> assertThat(e.error()).isEqualTo(S3Error.SIGNATURE_DOES_NOT_MATCH));
    }

    @Test
    void reorderedOrDroppedChunksBreakTheChain() {
        byte[] dropped = body(
                "400;chunk-signature=0055627c9e194cb4542bae2aa5492e3c1575bbb81b612b7d234b86a503ef5497\r\n", "DATA:1024", "\r\n",
                "0;chunk-signature=b6c6ea8a5354eaf15b3cb7646744f4275b71ea724fed81ceb9323e279d449df9\r\n", "\r\n");
        AwsChunkedInputStream in = new AwsChunkedInputStream(new ByteArrayInputStream(dropped),
                auth(SigV4.STREAMING_SIGNED, "4f232c4386841ef735655705268965c44a0e4690baa4adea153f7db9fa80a0a9"));
        assertThatThrownBy(() -> readAll(in)).isInstanceOf(S3Exception.class);
    }

    @Test
    void signedChunksWithSignedTrailer() throws IOException {
        byte[] body = body(
                "10000;chunk-signature=b474d8862b1487a5145d686f57f013e54db672cee1c953b3010fb58501ef5aa2\r\n", "DATA:65536", "\r\n",
                "400;chunk-signature=1c1344b170168f8e65b41376b44b20fe354e373826ccbbe2c1d40a8cae51e5c7\r\n", "DATA:1024", "\r\n",
                "0;chunk-signature=2ca2aba2005185cf7159c6277faf83795951dd77a3a99e6e65d5c9f85863f992\r\n",
                "x-amz-checksum-crc32c:sOO8/Q==\r\n",
                "x-amz-trailer-signature:d81f82fc3505edab99d459891051a732e8730629a2e4a59689829ca17fe2e435\r\n", "\r\n");
        AwsChunkedInputStream in = new AwsChunkedInputStream(new ByteArrayInputStream(body),
                auth(SigV4.STREAMING_SIGNED_TRAILER, "106e2a8a18243abcf37539882f36619c00e2dfc72633413f02d3b74544bfeb8e"));
        assertThat(readAll(in)).hasSize(66560);
        assertThat(in.trailers()).containsEntry("x-amz-checksum-crc32c", "sOO8/Q==");

        byte[] forged = new String(body, StandardCharsets.ISO_8859_1).replace("sOO8/Q==", "AAAAAA==")
                .getBytes(StandardCharsets.ISO_8859_1);
        AwsChunkedInputStream bad = new AwsChunkedInputStream(new ByteArrayInputStream(forged),
                auth(SigV4.STREAMING_SIGNED_TRAILER, "106e2a8a18243abcf37539882f36619c00e2dfc72633413f02d3b74544bfeb8e"));
        assertThatThrownBy(() -> readAll(bad)).isInstanceOfSatisfying(S3Exception.class,
                e -> assertThat(e.getMessage()).contains("trailer signature"));
    }

    @Test
    void unsignedChunksWithTrailerAsInTheDocumentation() throws IOException {
        byte[] body = body("2000\r\n", "DATA:8192", "\r\n", "2000\r\n", "DATA:8192", "\r\n", "400\r\n", "DATA:1024", "\r\n",
                "0\r\n", "x-amz-checksum-crc32:YABb/g==\n\r\n\r\n");
        AwsChunkedInputStream in = new AwsChunkedInputStream(new ByteArrayInputStream(body),
                auth(SigV4.STREAMING_UNSIGNED_TRAILER, "seed"));
        assertThat(readAll(in)).hasSize(17408);
        assertThat(in.trailers()).containsEntry("x-amz-checksum-crc32", "YABb/g==");
    }

    @Test
    void truncatedBodyIsIncomplete() {
        byte[] truncated = Arrays.copyOf(SIGNED, 30000);
        AwsChunkedInputStream in = new AwsChunkedInputStream(new ByteArrayInputStream(truncated),
                auth(SigV4.STREAMING_SIGNED, "4f232c4386841ef735655705268965c44a0e4690baa4adea153f7db9fa80a0a9"));
        assertThatThrownBy(() -> readAll(in))
                .isInstanceOfSatisfying(S3Exception.class, e -> assertThat(e.error()).isEqualTo(S3Error.INCOMPLETE_BODY));
    }
}
