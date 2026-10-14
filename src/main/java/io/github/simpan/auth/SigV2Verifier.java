package io.github.simpan.auth;

import io.github.simpan.api.S3Error;
import io.github.simpan.api.S3Exception;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Legacy Signature Version 2 (HMAC-SHA1), header and query-string forms. Still worth accepting: boto3 and other
 * tools fall back to it for presigned URLs unless told to use SigV4. Payloads are never signed in V2.
 */
public final class SigV2Verifier {

    /** Query parameters that are part of the signed resource. */
    private static final Set<String> SUBRESOURCES = Set.of("accelerate", "acl", "cors", "defaultObjectAcl", "delete",
            "lifecycle", "location", "logging", "notification", "partNumber", "policy", "requestPayment", "restore",
            "tagging", "torrent", "uploadId", "uploads", "versionId", "versioning", "versions", "website",
            "response-content-type", "response-content-language", "response-expires", "response-cache-control",
            "response-content-disposition", "response-content-encoding", "replication", "analytics", "metrics",
            "inventory", "select", "select-type", "object-lock", "storageClass");
    private static final Duration MAX_SKEW = Duration.ofMinutes(15);

    private final Credentials credentials;

    SigV2Verifier(Credentials credentials) {
        this.credentials = credentials;
    }

    AuthResult verifyHeader(SignableRequest req, String authorization, Instant now) {
        String creds = authorization.substring(4).trim();
        int colon = creds.lastIndexOf(':');
        if (colon <= 0) throw new S3Exception(S3Error.AUTHORIZATION_HEADER_MALFORMED, "Expected AWS AccessKeyId:Signature");
        String accessKey = creds.substring(0, colon);
        String signature = creds.substring(colon + 1);
        String amzDate = req.header("x-amz-date");
        String date = amzDate != null ? amzDate : req.header("date");
        if (date == null) throw new S3Exception(S3Error.ACCESS_DENIED, "AWS authentication requires a valid Date or x-amz-date header");
        try {
            Instant t = ZonedDateTime.parse(date.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
            if (Duration.between(t, now).abs().compareTo(MAX_SKEW) > 0) throw new S3Exception(S3Error.REQUEST_TIME_TOO_SKEWED);
        } catch (DateTimeParseException e) {
            throw new S3Exception(S3Error.ACCESS_DENIED, "Invalid date header");
        }
        String stringToSign = stringToSign(req, amzDate != null ? "" : date);
        return check(accessKey, signature, stringToSign);
    }

    AuthResult verifyQuery(SignableRequest req, Map<String, String> query, Instant now) {
        String accessKey = query.get("AWSAccessKeyId");
        String signature = query.get("Signature");
        String expires = query.get("Expires");
        if (accessKey == null || signature == null || expires == null) {
            throw new S3Exception(S3Error.ACCESS_DENIED, "Query-string authentication requires AWSAccessKeyId, Signature and Expires");
        }
        long exp;
        try {
            exp = Long.parseLong(expires);
        } catch (NumberFormatException e) {
            throw new S3Exception(S3Error.ACCESS_DENIED, "Invalid Expires");
        }
        if (now.getEpochSecond() > exp) throw new S3Exception(S3Error.ACCESS_DENIED, "Request has expired").with("Expires", expires);
        return check(accessKey, signature, stringToSign(req, expires));
    }

    private AuthResult check(String accessKey, String signature, String stringToSign) {
        String secret = credentials.secretFor(accessKey);
        if (secret == null) throw new S3Exception(S3Error.INVALID_ACCESS_KEY_ID).with("AWSAccessKeyId", accessKey);
        String expected;
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
            expected = Base64.getEncoder().encodeToString(mac.doFinal(stringToSign.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII), signature.getBytes(StandardCharsets.US_ASCII))) {
            throw new S3Exception(S3Error.SIGNATURE_DOES_NOT_MATCH).with("AWSAccessKeyId", accessKey)
                    .with("StringToSign", stringToSign);
        }
        return new AuthResult(accessKey, null, null, null, expected, SigV4.UNSIGNED_PAYLOAD, false);
    }

    /** Method, Content-MD5, Content-Type, Date (or Expires), x-amz-* headers, then the resource. */
    static String stringToSign(SignableRequest req, String dateOrExpires) {
        StringBuilder sb = new StringBuilder();
        sb.append(req.method()).append('\n');
        sb.append(nz(req.header("content-md5"))).append('\n');
        sb.append(nz(req.header("content-type"))).append('\n');
        sb.append(dateOrExpires).append('\n');
        TreeMap<String, List<String>> amz = new TreeMap<>();
        for (String name : headerNames(req)) {
            String lower = name.toLowerCase(Locale.ROOT);
            if (lower.startsWith("x-amz-")) {
                List<String> values = new ArrayList<>();
                for (String v : req.headers(name)) values.add(v.trim());
                amz.computeIfAbsent(lower, k -> new ArrayList<>()).addAll(values);
            }
        }
        for (Map.Entry<String, List<String>> e : amz.entrySet()) {
            sb.append(e.getKey()).append(':').append(String.join(",", e.getValue())).append('\n');
        }
        sb.append(req.virtualHostBucketPrefix()).append(req.rawPath());
        List<String[]> sub = new ArrayList<>();
        if (req.rawQuery() != null) {
            for (String part : req.rawQuery().split("&")) {
                int eq = part.indexOf('=');
                String k = eq < 0 ? part : part.substring(0, eq);
                if (SUBRESOURCES.contains(k)) sub.add(new String[] {k, eq < 0 ? null : SigV4.percentDecode(part.substring(eq + 1))});
            }
        }
        sub.sort((a, b) -> a[0].compareTo(b[0]));
        for (int i = 0; i < sub.size(); i++) {
            sb.append(i == 0 ? '?' : '&').append(sub.get(i)[0]);
            if (sub.get(i)[1] != null) sb.append('=').append(sub.get(i)[1]);
        }
        return sb.toString();
    }

    private static List<String> headerNames(SignableRequest req) {
        return req instanceof HeaderNames h ? h.headerNames() : Collections.emptyList();
    }

    private static String nz(String s) {
        return s == null ? "" : s.trim();
    }

    /** Implemented by requests that can enumerate their headers (needed for the x-amz-* block). */
    public interface HeaderNames {
        List<String> headerNames();
    }
}
