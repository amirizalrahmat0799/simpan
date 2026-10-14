package io.github.simpan.auth;

import io.github.simpan.api.S3Error;
import io.github.simpan.api.S3Exception;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Verifies AWS Signature Version 4, from the Authorization header or from a presigned URL's query string.
 *
 * <p>The canonical request is rebuilt from what the server received; a byte of difference from what the client
 * signed produces SignatureDoesNotMatch. Comparisons are constant-time.
 */
public final class SigV4Verifier {

    public static final DateTimeFormatter AMZ_DATE = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
            .withZone(ZoneOffset.UTC);
    private static final Duration MAX_SKEW = Duration.ofMinutes(15);
    private static final long MAX_PRESIGN_SECONDS = 7 * 24 * 3600;

    private final Credentials credentials;
    private final String region;
    private final SigV2Verifier v2;

    public SigV4Verifier(Credentials credentials, String region) {
        this.credentials = credentials;
        this.region = region;
        this.v2 = new SigV2Verifier(credentials);
    }

    public AuthResult verify(SignableRequest req, Instant now) {
        Map<String, String> query = parseQuery(req.rawQuery());
        if (query.containsKey("X-Amz-Algorithm")) return verifyPresigned(req, query, now);
        String auth = req.header("authorization");
        if (auth == null && query.containsKey("AWSAccessKeyId") && query.containsKey("Signature")) {
            return v2.verifyQuery(req, query, now);
        }
        if (auth == null) {
            throw new S3Exception(S3Error.ACCESS_DENIED, "Anonymous requests are not allowed; sign requests with SigV4");
        }
        if (auth.startsWith("AWS ")) return v2.verifyHeader(req, auth, now);
        if (!auth.startsWith(SigV4.ALGORITHM + " ")) {
            throw new S3Exception(S3Error.AUTHORIZATION_HEADER_MALFORMED, "Unsupported authorization type");
        }
        Map<String, String> parts = new HashMap<>();
        for (String p : auth.substring(SigV4.ALGORITHM.length() + 1).split(",")) {
            int eq = p.indexOf('=');
            if (eq > 0) parts.put(p.substring(0, eq).trim(), p.substring(eq + 1).trim());
        }
        String credential = parts.get("Credential");
        String signedHeaders = parts.get("SignedHeaders");
        String signature = parts.get("Signature");
        if (credential == null || signedHeaders == null || signature == null) {
            throw new S3Exception(S3Error.AUTHORIZATION_HEADER_MALFORMED,
                    "The authorization header must contain Credential, SignedHeaders and Signature");
        }

        String amzDate = req.header("x-amz-date");
        if (amzDate == null) {
            String date = req.header("date");
            if (date == null) {
                throw new S3Exception(S3Error.ACCESS_DENIED, "AWS authentication requires a valid Date or x-amz-date header");
            }
            try {
                amzDate = AMZ_DATE.format(ZonedDateTime.parse(date, DateTimeFormatter.RFC_1123_DATE_TIME));
            } catch (DateTimeParseException e) {
                throw new S3Exception(S3Error.ACCESS_DENIED, "Invalid Date header");
            }
        }
        Instant requestTime = parseAmzDate(amzDate, S3Error.ACCESS_DENIED);
        if (Duration.between(requestTime, now).abs().compareTo(MAX_SKEW) > 0) {
            throw new S3Exception(S3Error.REQUEST_TIME_TOO_SKEWED).with("RequestTime", amzDate)
                    .with("ServerTime", AMZ_DATE.format(now)).with("MaxAllowedSkewMilliseconds", "900000");
        }

        String payloadHash = req.header("x-amz-content-sha256");
        if (payloadHash == null) {
            throw new S3Exception(S3Error.INVALID_REQUEST, "Missing required header for this request: x-amz-content-sha256");
        }
        Scope scope = parseScope(credential, amzDate, S3Error.AUTHORIZATION_HEADER_MALFORMED);
        String canonical = canonicalRequest(req, null, signedHeaders, payloadHash);
        return check(scope, amzDate, canonical, signature, payloadHash, false);
    }

    private AuthResult verifyPresigned(SignableRequest req, Map<String, String> query, Instant now) {
        S3Error bad = S3Error.AUTHORIZATION_QUERY_PARAMETERS_ERROR;
        if (!SigV4.ALGORITHM.equals(query.get("X-Amz-Algorithm"))) {
            throw new S3Exception(bad, "X-Amz-Algorithm only supports \"AWS4-HMAC-SHA256\"");
        }
        String credential = query.get("X-Amz-Credential");
        String amzDate = query.get("X-Amz-Date");
        String expires = query.get("X-Amz-Expires");
        String signedHeaders = query.get("X-Amz-SignedHeaders");
        String signature = query.get("X-Amz-Signature");
        if (credential == null || amzDate == null || expires == null || signedHeaders == null || signature == null) {
            throw new S3Exception(bad, "Query-string authentication requires the X-Amz-Algorithm, X-Amz-Credential, "
                    + "X-Amz-Signature, X-Amz-Date, X-Amz-SignedHeaders, and X-Amz-Expires parameters.");
        }
        Instant signedAt = parseAmzDate(amzDate, bad);
        long ttl;
        try {
            ttl = Long.parseLong(expires);
        } catch (NumberFormatException e) {
            throw new S3Exception(bad, "X-Amz-Expires should be a number");
        }
        if (ttl < 1 || ttl > MAX_PRESIGN_SECONDS) {
            throw new S3Exception(bad, "X-Amz-Expires must be between 1 and 604800 seconds");
        }
        if (now.isAfter(signedAt.plusSeconds(ttl))) {
            throw new S3Exception(S3Error.ACCESS_DENIED, "Request has expired").with("X-Amz-Expires", expires)
                    .with("Expires", AMZ_DATE.format(signedAt.plusSeconds(ttl))).with("ServerTime", AMZ_DATE.format(now));
        }
        if (signedAt.isAfter(now.plus(MAX_SKEW))) {
            throw new S3Exception(S3Error.ACCESS_DENIED, "Request is not valid yet");
        }
        Scope scope = parseScope(credential, amzDate, bad);
        String payloadHash = query.getOrDefault("X-Amz-Content-Sha256", SigV4.UNSIGNED_PAYLOAD);
        String canonical = canonicalRequest(req, "X-Amz-Signature", signedHeaders, payloadHash);
        return check(scope, amzDate, canonical, signature, payloadHash, true);
    }

    private AuthResult check(Scope scope, String amzDate, String canonical, String signature, String payloadHash,
            boolean presigned) {
        String secret = credentials.secretFor(scope.accessKey);
        if (secret == null) throw new S3Exception(S3Error.INVALID_ACCESS_KEY_ID).with("AWSAccessKeyId", scope.accessKey);
        String stringToSign = SigV4.stringToSign(amzDate, scope.value, canonical);
        byte[] key = SigV4.signingKey(secret, scope.date, scope.region, scope.service);
        String expected = SigV4.hmacHex(key, stringToSign);
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                signature.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII))) {
            throw new S3Exception(S3Error.SIGNATURE_DOES_NOT_MATCH).with("AWSAccessKeyId", scope.accessKey)
                    .with("StringToSign", stringToSign).with("CanonicalRequest", canonical);
        }
        return new AuthResult(scope.accessKey, key, amzDate, scope.value, expected, payloadHash, presigned);
    }

    /** Builds the canonical request; {@code skipParam} is left out of the query (the presigned signature). */
    static String canonicalRequest(SignableRequest req, String skipParam,
            String signedHeaders, String payloadHash) {
        StringBuilder sb = new StringBuilder(512);
        sb.append(req.method()).append('\n');
        String path = req.rawPath() == null || req.rawPath().isEmpty() ? "/" : req.rawPath();
        sb.append(SigV4.uriEncode(SigV4.percentDecode(path), true)).append('\n');
        sb.append(canonicalQuery(req.rawQuery(), skipParam)).append('\n');
        List<String> names = List.of(signedHeaders.toLowerCase(Locale.ROOT).split(";"));
        for (String name : names) {
            List<String> values = req.headers(name);
            List<String> canon = new ArrayList<>(values.size());
            for (String v : values) canon.add(SigV4.canonicalValue(v));
            sb.append(name).append(':').append(String.join(",", canon)).append('\n');
        }
        sb.append('\n').append(String.join(";", names)).append('\n').append(payloadHash);
        return sb.toString();
    }

    static String canonicalQuery(String rawQuery, String skipParam) {
        if (rawQuery == null || rawQuery.isEmpty()) return "";
        List<String[]> pairs = new ArrayList<>();
        for (String part : rawQuery.split("&")) {
            if (part.isEmpty()) continue;
            int eq = part.indexOf('=');
            String k = SigV4.percentDecode(eq < 0 ? part : part.substring(0, eq));
            String v = eq < 0 ? "" : SigV4.percentDecode(part.substring(eq + 1));
            if (k.equals(skipParam)) continue;
            pairs.add(new String[] {SigV4.uriEncode(k, false), SigV4.uriEncode(v, false)});
        }
        pairs.sort((a, b) -> a[0].equals(b[0]) ? a[1].compareTo(b[1]) : a[0].compareTo(b[0]));
        StringBuilder sb = new StringBuilder();
        for (String[] p : pairs) {
            if (sb.length() > 0) sb.append('&');
            sb.append(p[0]).append('=').append(p[1]);
        }
        return sb.toString();
    }

    /** Decoded query parameters (first value wins). */
    public static Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> m = new TreeMap<>();
        if (rawQuery == null || rawQuery.isEmpty()) return m;
        for (String part : rawQuery.split("&")) {
            if (part.isEmpty()) continue;
            int eq = part.indexOf('=');
            String k = SigV4.percentDecode(eq < 0 ? part : part.substring(0, eq));
            String v = eq < 0 ? "" : SigV4.percentDecode(part.substring(eq + 1));
            m.putIfAbsent(k, v);
        }
        return m;
    }

    private static Instant parseAmzDate(String amzDate, S3Error error) {
        try {
            return ZonedDateTime.parse(amzDate, AMZ_DATE).toInstant();
        } catch (DateTimeParseException e) {
            throw new S3Exception(error, "X-Amz-Date must be in the ISO8601 Long Format \"yyyyMMdd'T'HHmmss'Z'\"");
        }
    }

    private record Scope(String accessKey, String date, String region, String service, String value) {}

    private Scope parseScope(String credential, String amzDate, S3Error error) {
        String[] p = credential.split("/");
        if (p.length != 5 || !"aws4_request".equals(p[4])) {
            throw new S3Exception(error, "Credential must be AccessKey/YYYYMMDD/region/s3/aws4_request");
        }
        if (!amzDate.startsWith(p[1])) {
            throw new S3Exception(error, "Credential date " + p[1] + " does not match the request date " + amzDate);
        }
        if (!"s3".equals(p[3])) throw new S3Exception(error, "Credential service must be s3, not " + p[3]);
        if (!region.equals(p[2])) {
            throw new S3Exception(error, "The region '" + p[2] + "' is wrong; expecting '" + region + "'")
                    .with("Region", region);
        }
        return new Scope(p[0], p[1], p[2], p[3], p[1] + "/" + p[2] + "/" + p[3] + "/aws4_request");
    }
}
