package io.github.simpan.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.simpan.api.S3Error;
import io.github.simpan.api.S3Exception;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * Signatures from the Amazon S3 API reference ("Signature Calculations for the Authorization Header", "Query
 * String Authentication" and "Signature Calculations for Streaming Upload"), reproduced byte for byte.
 */
class SigV4VerifierTest {

    static final String AK = "AKIAIOSFODNN7EXAMPLE";
    static final String SK = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY";
    static final Instant WHEN = Instant.parse("2013-05-24T00:00:00Z");
    static final String CRED = "Credential=AKIAIOSFODNN7EXAMPLE/20130524/us-east-1/s3/aws4_request";

    final SigV4Verifier verifier = new SigV4Verifier(new Credentials().add(AK, SK), "us-east-1");

    @Test
    void getObjectWithRange() {
        AuthResult r = verifier.verify(FakeRequest.of("GET", "/test.txt", null,
                "Host", "examplebucket.s3.amazonaws.com",
                "Range", "bytes=0-9",
                "x-amz-content-sha256", SigV4.EMPTY_SHA256,
                "x-amz-date", "20130524T000000Z",
                "Authorization", "AWS4-HMAC-SHA256 " + CRED + ",SignedHeaders=host;range;x-amz-content-sha256;x-amz-date,"
                        + "Signature=f0e8bdb87c964420e857bd35b5d6ed310bd44f0170aba48dd91039c6036bdb41"), WHEN);
        assertThat(r.accessKey()).isEqualTo(AK);
        assertThat(r.expectedSha256()).isEqualTo(SigV4.EMPTY_SHA256);
    }

    @Test
    void putObject() {
        verifier.verify(FakeRequest.of("PUT", "/test%24file.text", null,
                "Host", "examplebucket.s3.amazonaws.com",
                "Date", "Fri, 24 May 2013 00:00:00 GMT",
                "x-amz-date", "20130524T000000Z",
                "x-amz-storage-class", "REDUCED_REDUNDANCY",
                "x-amz-content-sha256", "44ce7dd67c959e0d3524ffac1771dfbba87d2b6b4b4e99e42034a8b803f8b072",
                "Authorization", "AWS4-HMAC-SHA256 " + CRED
                        + ",SignedHeaders=date;host;x-amz-content-sha256;x-amz-date;x-amz-storage-class,"
                        + "Signature=98ad721746da40c64f1a55b78f14c238d841ea1380cd77a1b5971af0ece108bd"), WHEN);
    }

    @Test
    void getBucketLifecycleSubresource() {
        verifier.verify(FakeRequest.of("GET", "/", "lifecycle",
                "Host", "examplebucket.s3.amazonaws.com",
                "x-amz-date", "20130524T000000Z",
                "x-amz-content-sha256", SigV4.EMPTY_SHA256,
                "Authorization", "AWS4-HMAC-SHA256 " + CRED + ",SignedHeaders=host;x-amz-content-sha256;x-amz-date,"
                        + "Signature=fea454ca298b7da1c68078a5d1bdbfbbe0d65c699e0f91ac7a200a0136783543"), WHEN);
    }

    @Test
    void listObjectsQueryIsSorted() {
        verifier.verify(FakeRequest.of("GET", "/", "max-keys=2&prefix=J",
                "Host", "examplebucket.s3.amazonaws.com",
                "x-amz-date", "20130524T000000Z",
                "x-amz-content-sha256", SigV4.EMPTY_SHA256,
                "Authorization", "AWS4-HMAC-SHA256 " + CRED + ",SignedHeaders=host;x-amz-content-sha256;x-amz-date,"
                        + "Signature=34b48302e7b5fa45bde8084f4b7868a86f0a534bc59db6670ed5711ef69dc6f7"), WHEN);
    }

    @Test
    void streamingUploadSeedSignature() {
        AuthResult r = verifier.verify(FakeRequest.of("PUT", "/examplebucket/chunkObject.txt", null,
                "Host", "s3.amazonaws.com",
                "x-amz-date", "20130524T000000Z",
                "x-amz-storage-class", "REDUCED_REDUNDANCY",
                "x-amz-content-sha256", "STREAMING-AWS4-HMAC-SHA256-PAYLOAD",
                "Content-Encoding", "aws-chunked",
                "x-amz-decoded-content-length", "66560",
                "Content-Length", "66824",
                "Authorization", "AWS4-HMAC-SHA256 " + CRED + ",SignedHeaders=content-encoding;content-length;host;"
                        + "x-amz-content-sha256;x-amz-date;x-amz-decoded-content-length;x-amz-storage-class,"
                        + "Signature=4f232c4386841ef735655705268965c44a0e4690baa4adea153f7db9fa80a0a9"), WHEN);
        assertThat(r.streaming()).isTrue();
        assertThat(r.signedChunks()).isTrue();
        assertThat(r.signature()).isEqualTo("4f232c4386841ef735655705268965c44a0e4690baa4adea153f7db9fa80a0a9");
    }

    @Test
    void presignedUrl() {
        String q = "X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Credential=AKIAIOSFODNN7EXAMPLE%2F20130524%2Fus-east-1%2Fs3"
                + "%2Faws4_request&X-Amz-Date=20130524T000000Z&X-Amz-Expires=86400&X-Amz-SignedHeaders=host"
                + "&X-Amz-Signature=aeeed9bbccd4d02ee5c0109b86d86835f995330da4c265957d157751f604d404";
        AuthResult r = verifier.verify(FakeRequest.of("GET", "/test.txt", q, "Host", "examplebucket.s3.amazonaws.com"),
                WHEN.plusSeconds(3600));
        assertThat(r.presigned()).isTrue();
        assertThat(r.payloadHash()).isEqualTo(SigV4.UNSIGNED_PAYLOAD);

        assertThatThrownBy(() -> verifier.verify(FakeRequest.of("GET", "/test.txt", q, "Host",
                "examplebucket.s3.amazonaws.com"), WHEN.plusSeconds(86401)))
                .isInstanceOfSatisfying(S3Exception.class, e -> {
                    assertThat(e.error()).isEqualTo(S3Error.ACCESS_DENIED);
                    assertThat(e.getMessage()).contains("expired");
                });
        assertThatThrownBy(() -> verifier.verify(FakeRequest.of("GET", "/test2.txt", q, "Host",
                "examplebucket.s3.amazonaws.com"), WHEN.plusSeconds(10)))
                .isInstanceOfSatisfying(S3Exception.class, e -> assertThat(e.error()).isEqualTo(S3Error.SIGNATURE_DOES_NOT_MATCH));
    }

    @Test
    void rejectsTamperingSkewAndUnknownKeys() {
        String good = "AWS4-HMAC-SHA256 " + CRED + ",SignedHeaders=host;range;x-amz-content-sha256;x-amz-date,"
                + "Signature=f0e8bdb87c964420e857bd35b5d6ed310bd44f0170aba48dd91039c6036bdb41";
        FakeRequest tampered = FakeRequest.of("GET", "/test.txt", null, "Host", "examplebucket.s3.amazonaws.com",
                "Range", "bytes=0-99", "x-amz-content-sha256", SigV4.EMPTY_SHA256, "x-amz-date", "20130524T000000Z",
                "Authorization", good);
        assertThatThrownBy(() -> verifier.verify(tampered, WHEN))
                .isInstanceOfSatisfying(S3Exception.class, e -> {
                    assertThat(e.error()).isEqualTo(S3Error.SIGNATURE_DOES_NOT_MATCH);
                    assertThat(e.fields()).containsKey("CanonicalRequest");
                });

        FakeRequest original = FakeRequest.of("GET", "/test.txt", null, "Host", "examplebucket.s3.amazonaws.com",
                "Range", "bytes=0-9", "x-amz-content-sha256", SigV4.EMPTY_SHA256, "x-amz-date", "20130524T000000Z",
                "Authorization", good);
        assertThatThrownBy(() -> verifier.verify(original, WHEN.plusSeconds(16 * 60)))
                .isInstanceOfSatisfying(S3Exception.class, e -> assertThat(e.error()).isEqualTo(S3Error.REQUEST_TIME_TOO_SKEWED));

        SigV4Verifier otherKeys = new SigV4Verifier(new Credentials().add("someone-else", "secret-123"), "us-east-1");
        assertThatThrownBy(() -> otherKeys.verify(original, WHEN))
                .isInstanceOfSatisfying(S3Exception.class, e -> assertThat(e.error()).isEqualTo(S3Error.INVALID_ACCESS_KEY_ID));

        SigV4Verifier otherRegion = new SigV4Verifier(new Credentials().add(AK, SK), "ap-southeast-5");
        assertThatThrownBy(() -> otherRegion.verify(original, WHEN))
                .isInstanceOfSatisfying(S3Exception.class, e -> {
                    assertThat(e.error()).isEqualTo(S3Error.AUTHORIZATION_HEADER_MALFORMED);
                    assertThat(e.fields()).containsEntry("Region", "ap-southeast-5");
                });

        assertThatThrownBy(() -> verifier.verify(FakeRequest.of("GET", "/", null, "Host", "x"), WHEN))
                .isInstanceOfSatisfying(S3Exception.class, e -> assertThat(e.error()).isEqualTo(S3Error.ACCESS_DENIED));
    }

    @Test
    void uriEncodingFollowsSigV4() {
        assertThat(SigV4.uriEncode("a b+c/d~e_f-g.h*", true)).isEqualTo("a%20b%2Bc/d~e_f-g.h%2A");
        assertThat(SigV4.uriEncode("a/b", false)).isEqualTo("a%2Fb");
        assertThat(SigV4.uriEncode("résumé😀", true)).isEqualTo("r%C3%A9sum%C3%A9%F0%9F%98%80");
        assertThat(SigV4.percentDecode("a%20b+c%2Fd%F0%9F%98%80")).isEqualTo("a b+c/d😀");
        assertThat(SigV4.percentDecode("100%")).isEqualTo("100%");
    }

    @Test
    void canonicalQuerySortsAndEncodes() {
        assertThat(SigV4Verifier.canonicalQuery("prefix=J&max-keys=2&uploads", null)).isEqualTo("max-keys=2&prefix=J&uploads=");
        assertThat(SigV4Verifier.canonicalQuery("prefix=a%20b%2Fc&delimiter=%2F", null))
                .isEqualTo("delimiter=%2F&prefix=a%20b%2Fc");
    }
}
