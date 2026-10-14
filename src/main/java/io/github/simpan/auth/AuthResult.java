package io.github.simpan.auth;

/**
 * A verified request.
 *
 * @param payloadHash  the x-amz-content-sha256 value: a hex SHA-256 the body must match, UNSIGNED-PAYLOAD, or a
 *                     STREAMING-* mode
 * @param signature    the request signature, which seeds the chain of chunk signatures in streaming uploads
 */
public record AuthResult(
        String accessKey, byte[] signingKey, String amzDate, String scope, String signature, String payloadHash,
        boolean presigned) {

    public boolean streaming() {
        return payloadHash.startsWith("STREAMING-");
    }

    public boolean signedChunks() {
        return SigV4.STREAMING_SIGNED.equals(payloadHash) || SigV4.STREAMING_SIGNED_TRAILER.equals(payloadHash);
    }

    public boolean trailer() {
        return payloadHash.endsWith("-TRAILER");
    }

    /** The body must hash to this hex SHA-256, or null if the payload is not signed this way. */
    public String expectedSha256() {
        return payloadHash.length() == 64 && payloadHash.chars().allMatch(c -> Character.digit(c, 16) >= 0)
                ? payloadHash.toLowerCase(java.util.Locale.ROOT) : null;
    }
}
