package io.github.simpan.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.simpan.api.S3Error;
import io.github.simpan.api.S3Exception;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Examples from the Amazon S3 Signature Version 2 documentation ("Signing and Authenticating REST Requests"). */
class SigV2VerifierTest {

    final SigV4Verifier verifier = new SigV4Verifier(
            new Credentials().add("AKIAIOSFODNN7EXAMPLE", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"), "us-east-1");

    @Test
    void objectGet() {
        verifier.verify(FakeRequest.of("GET", "/awsexamplebucket1/photos/puppy.jpg", null,
                "Host", "s3.us-west-1.amazonaws.com",
                "Date", "Tue, 27 Mar 2007 19:36:42 +0000",
                "Authorization", "AWS AKIAIOSFODNN7EXAMPLE:qgk2+6Sv9/oM7G3qLEjTH1a1l1g="),
                Instant.parse("2007-03-27T19:36:42Z"));
    }

    @Test
    void stringToSignIncludesAmzHeadersAndSubresources() {
        FakeRequest r = FakeRequest.of("PUT", "/b/k", "uploadId=abc&partNumber=2&foo=bar",
                "Content-Type", "text/plain", "X-Amz-Meta-B", "2", "x-amz-meta-a", "1");
        assertThat(SigV2Verifier.stringToSign(r, "123"))
                .isEqualTo("PUT\n\ntext/plain\n123\nx-amz-meta-a:1\nx-amz-meta-b:2\n/b/k?partNumber=2&uploadId=abc");
    }

    @Test
    void queryStringAuthExpires() {
        FakeRequest r = FakeRequest.of("GET", "/b/k", "AWSAccessKeyId=AKIAIOSFODNN7EXAMPLE&Expires=1&Signature=x");
        assertThatThrownBy(() -> verifier.verify(r, Instant.ofEpochSecond(2)))
                .isInstanceOfSatisfying(S3Exception.class, e -> assertThat(e.getMessage()).contains("expired"));
        assertThatThrownBy(() -> verifier.verify(r, Instant.ofEpochSecond(0)))
                .isInstanceOfSatisfying(S3Exception.class, e -> assertThat(e.error()).isEqualTo(S3Error.SIGNATURE_DOES_NOT_MATCH));
    }
}
