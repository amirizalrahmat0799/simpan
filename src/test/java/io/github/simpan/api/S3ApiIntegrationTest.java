package io.github.simpan.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;
import java.util.Random;
import java.util.zip.CRC32C;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Boots the real server on a random port and talks raw HTTP to it. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "simpan.access-key=it-access", "simpan.secret-key=it-secret-key", "simpan.fsync=false",
    "management.server.port=0"})
class S3ApiIntegrationTest {

    @TempDir
    static Path dataDir;

    @DynamicPropertySource
    static void dataDir(DynamicPropertyRegistry r) {
        r.add("simpan.data-dir", () -> dataDir.toString());
    }

    @LocalServerPort
    int port;

    final HttpClient http = HttpClient.newHttpClient();
    TestSigner signer;

    @BeforeEach
    void setUp() throws Exception {
        signer = new TestSigner(URI.create("http://127.0.0.1:" + port), "it-access", "it-secret-key");
        HttpResponse<String> created = send("PUT", "/itest", null, new byte[0]);
        assertThat(created.statusCode()).as(created.body()).isIn(200, 409);
    }

    HttpResponse<String> send(String method, String path, String query, byte[] body) throws Exception {
        TestSigner.Signed s = signer.sign(method, path, query, Map.of(), TestSigner.sha256Hex(body));
        return http.send(s.request().method(method, HttpRequest.BodyPublishers.ofByteArray(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void streamingUploadWithSignedChunksAndSignedTrailer() throws Exception {
        byte[] data = new byte[200_000];
        new Random(1).nextBytes(data);
        CRC32C crc = new CRC32C();
        crc.update(data);
        String crcB64 = Base64.getEncoder().encodeToString(java.nio.ByteBuffer.allocate(4).putInt((int) crc.getValue()).array());

        TestSigner.Signed s = signer.sign("PUT", "/itest/streamed.bin", null, Map.of(
                "content-encoding", "aws-chunked",
                "x-amz-decoded-content-length", String.valueOf(data.length),
                "x-amz-trailer", "x-amz-checksum-crc32c"), "STREAMING-AWS4-HMAC-SHA256-PAYLOAD-TRAILER");
        byte[] body = TestSigner.chunkedBody(s, data, 65536, "x-amz-checksum-crc32c", crcB64);
        HttpResponse<String> put = http.send(s.request().PUT(HttpRequest.BodyPublishers.ofByteArray(body)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(put.statusCode()).as(put.body()).isEqualTo(200);
        assertThat(put.headers().firstValue("x-amz-checksum-crc32c")).hasValue(crcB64);

        TestSigner.Signed get = signer.sign("GET", "/itest/streamed.bin", null, Map.of("x-amz-checksum-mode", "ENABLED"),
                TestSigner.sha256Hex(new byte[0]));
        HttpResponse<byte[]> got = http.send(get.request().GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        assertThat(got.body()).isEqualTo(data);
        assertThat(got.headers().firstValue("content-encoding")).as("aws-chunked is never stored").isEmpty();
    }

    @Test
    void tamperedStreamingUploadIsRejectedAndNotStored() throws Exception {
        byte[] data = "x".repeat(100_000).getBytes(StandardCharsets.US_ASCII);
        TestSigner.Signed s = signer.sign("PUT", "/itest/tampered.bin", null, Map.of(
                "content-encoding", "aws-chunked",
                "x-amz-decoded-content-length", String.valueOf(data.length)), "STREAMING-AWS4-HMAC-SHA256-PAYLOAD");
        byte[] body = TestSigner.chunkedBody(s, data, 65536, null, null);
        body[90_000] = 'y';
        HttpResponse<String> put = http.send(s.request().PUT(HttpRequest.BodyPublishers.ofByteArray(body)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(put.statusCode()).isEqualTo(403);
        assertThat(put.body()).contains("<Code>SignatureDoesNotMatch</Code>");
        assertThat(send("HEAD", "/itest/tampered.bin", null, new byte[0]).statusCode()).isEqualTo(404);
    }

    @Test
    void payloadHashMismatchIsRejected() throws Exception {
        TestSigner.Signed s = signer.sign("PUT", "/itest/h.txt", null, Map.of(), TestSigner.sha256Hex("expected".getBytes()));
        HttpResponse<String> r = http.send(s.request().PUT(HttpRequest.BodyPublishers.ofString("different")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).isEqualTo(400);
        assertThat(r.body()).contains("<Code>XAmzContentSHA256Mismatch</Code>");
    }

    @Test
    void errorsAreS3XmlDocuments() throws Exception {
        HttpResponse<String> r = send("GET", "/itest/missing", null, new byte[0]);
        assertThat(r.statusCode()).isEqualTo(404);
        assertThat(r.headers().firstValue("content-type")).hasValueSatisfying(v -> assertThat(v).startsWith("application/xml"));
        assertThat(r.body()).contains("<Code>NoSuchKey</Code>", "<Key>missing</Key>", "<Resource>/itest/missing</Resource>",
                "<RequestId>");
        assertThat(r.headers().firstValue("x-amz-request-id")).isPresent();

        HttpResponse<String> head = send("HEAD", "/itest/missing", null, new byte[0]);
        assertThat(head.statusCode()).isEqualTo(404);
        assertThat(head.body()).isEmpty();

        assertThat(send("GET", "/itest", "tagging=", new byte[0]).body()).contains("<Code>NotImplemented</Code>");
        HttpResponse<String> anon = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/itest")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(anon.statusCode()).isEqualTo(403);
    }

    @Test
    void hostileXmlIsRejected() throws Exception {
        String xxe = "<?xml version=\"1.0\"?><!DOCTYPE d [<!ENTITY x SYSTEM \"file:///etc/passwd\">]>"
                + "<Delete><Object><Key>&x;</Key></Object></Delete>";
        HttpResponse<String> r = send("POST", "/itest", "delete=", xxe.getBytes(StandardCharsets.UTF_8));
        assertThat(r.statusCode()).isEqualTo(400);
        assertThat(r.body()).contains("<Code>MalformedXML</Code>").doesNotContain("root:");
    }

    @Test
    void keysWithSpecialCharactersRoundTrip() throws Exception {
        String path = "/itest/" + io.github.simpan.auth.SigV4.uriEncode("dir/a b+c%d [x] 😀.txt", true);
        assertThat(send("PUT", path, null, "odd".getBytes()).statusCode()).isEqualTo(200);
        HttpResponse<String> list = send("GET", "/itest", "list-type=2&prefix=dir%2F", new byte[0]);
        assertThat(list.body()).contains("<Key>dir/a b+c%d [x] 😀.txt</Key>");
        assertThat(send("GET", path, null, new byte[0]).body()).isEqualTo("odd");
    }
}
