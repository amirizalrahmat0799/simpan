package io.github.simpan.api;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * A small, independent SigV4 client for integration tests, including streaming (aws-chunked) uploads with signed
 * chunks and a signed trailer, the mode the AWS SDKs for Java and JavaScript use over plain HTTP.
 */
final class TestSigner {

    private static final DateTimeFormatter AMZ = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    private static final HexFormat HEX = HexFormat.of();

    private final URI endpoint;
    private final String accessKey;
    private final String secretKey;

    TestSigner(URI endpoint, String accessKey, String secretKey) {
        this.endpoint = endpoint;
        this.accessKey = accessKey;
        this.secretKey = secretKey;
    }

    record Signed(HttpRequest.Builder request, String signature, String amzDate, String scope, byte[] key) {}

    /** Signs a request; {@code path} must already be URI-encoded, {@code query} canonical (sorted, encoded). */
    Signed sign(String method, String path, String query, Map<String, String> extraHeaders, String payloadHash) {
        String amzDate = AMZ.format(Instant.now());
        String date = amzDate.substring(0, 8);
        String scope = date + "/us-east-1/s3/aws4_request";
        TreeMap<String, String> headers = new TreeMap<>();
        headers.put("host", endpoint.getHost() + ":" + endpoint.getPort());
        headers.put("x-amz-date", amzDate);
        headers.put("x-amz-content-sha256", payloadHash);
        extraHeaders.forEach((k, v) -> headers.put(k.toLowerCase(Locale.ROOT), v));
        StringBuilder canonical = new StringBuilder();
        canonical.append(method).append('\n').append(path).append('\n').append(query == null ? "" : query).append('\n');
        headers.forEach((k, v) -> canonical.append(k).append(':').append(v.trim()).append('\n'));
        String signedHeaders = String.join(";", headers.keySet());
        canonical.append('\n').append(signedHeaders).append('\n').append(payloadHash);
        String stringToSign = "AWS4-HMAC-SHA256\n" + amzDate + "\n" + scope + "\n" + sha256Hex(canonical.toString().getBytes(StandardCharsets.UTF_8));
        byte[] key = hmac(hmac(hmac(hmac(("AWS4" + secretKey).getBytes(StandardCharsets.UTF_8), date), "us-east-1"), "s3"), "aws4_request");
        String signature = HEX.formatHex(hmac(key, stringToSign));
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(endpoint + path + (query == null || query.isEmpty() ? "" : "?" + query)));
        headers.forEach((k, v) -> {
            if (!k.equals("host") && !k.equals("content-length")) b.header(k, v);
        });
        b.header("Authorization", "AWS4-HMAC-SHA256 Credential=" + accessKey + "/" + scope + ", SignedHeaders="
                + signedHeaders + ", Signature=" + signature);
        return new Signed(b, signature, amzDate, scope, key);
    }

    /** Builds an aws-chunked body with chained chunk signatures and, if given, a signed trailing checksum. */
    static byte[] chunkedBody(Signed s, byte[] data, int chunkSize, String trailerName, String trailerValue) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        String prev = s.signature();
        for (int off = 0; ; off += chunkSize) {
            int len = Math.max(0, Math.min(chunkSize, data.length - off));
            byte[] chunk = new byte[len];
            System.arraycopy(data, Math.min(off, data.length), chunk, 0, len);
            String sts = "AWS4-HMAC-SHA256-PAYLOAD\n" + s.amzDate() + "\n" + s.scope() + "\n" + prev + "\n"
                    + sha256Hex(new byte[0]) + "\n" + sha256Hex(chunk);
            prev = HEX.formatHex(hmac(s.key(), sts));
            out.writeBytes((Integer.toHexString(len) + ";chunk-signature=" + prev + "\r\n").getBytes(StandardCharsets.US_ASCII));
            out.writeBytes(chunk);
            if (len > 0) out.writeBytes("\r\n".getBytes(StandardCharsets.US_ASCII));
            if (len == 0) break;
        }
        if (trailerName != null) {
            String canonical = trailerName + ":" + trailerValue + "\n";
            String sts = "AWS4-HMAC-SHA256-TRAILER\n" + s.amzDate() + "\n" + s.scope() + "\n" + prev + "\n"
                    + sha256Hex(canonical.getBytes(StandardCharsets.UTF_8));
            out.writeBytes((trailerName + ":" + trailerValue + "\r\n").getBytes(StandardCharsets.US_ASCII));
            out.writeBytes(("x-amz-trailer-signature:" + HEX.formatHex(hmac(s.key(), sts)) + "\r\n").getBytes(StandardCharsets.US_ASCII));
        }
        out.writeBytes("\r\n".getBytes(StandardCharsets.US_ASCII));
        return out.toByteArray();
    }

    static String sha256Hex(byte[] data) {
        try {
            return HEX.formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static byte[] hmac(byte[] key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
