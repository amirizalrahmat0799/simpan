package io.github.simpan.io;

import io.github.simpan.api.S3Error;
import io.github.simpan.api.S3Exception;
import io.github.simpan.auth.AuthResult;
import io.github.simpan.auth.SigV4;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Decodes an {@code aws-chunked} request body, the framing AWS SDKs use for streaming uploads:
 *
 * <pre>
 * hex-size[;chunk-signature=sig]\r\n
 * data\r\n
 * ...
 * 0[;chunk-signature=sig]\r\n
 * [x-amz-checksum-crc32:base64\r\n
 *  x-amz-trailer-signature:sig\r\n]
 * \r\n
 * </pre>
 *
 * <p>With signed chunks, each chunk's signature is checked against the chain that starts at the request
 * signature; the stream throws at the end of a chunk whose signature is wrong, so callers that stage data before
 * committing never commit a tampered body. Memory use is constant: chunk data is hashed as it streams through.
 */
public final class AwsChunkedInputStream extends InputStream {

    private static final int MAX_LINE = 4096;
    private static final long MAX_CHUNK = 64L * 1024 * 1024;

    private final InputStream in;
    private final AuthResult auth;
    private final boolean signedChunks;
    private final boolean expectTrailer;
    private final Map<String, String> trailers = new LinkedHashMap<>();

    private String previousSignature;
    private String chunkSignature;
    private MessageDigest chunkHash;
    private long remaining;
    private boolean eof;
    private long decodedBytes;

    public AwsChunkedInputStream(InputStream in, AuthResult auth) {
        this.in = in;
        this.auth = auth;
        this.signedChunks = auth.signedChunks();
        this.expectTrailer = auth.trailer();
        this.previousSignature = auth.signature();
    }

    /** Trailing headers (lowercase names), available once the stream is fully read. */
    public Map<String, String> trailers() {
        return trailers;
    }

    public long decodedBytes() {
        return decodedBytes;
    }

    @Override
    public int read() throws IOException {
        byte[] one = new byte[1];
        int n = read(one, 0, 1);
        return n < 0 ? -1 : one[0] & 0xFF;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        if (eof) return -1;
        if (remaining == 0) {
            startChunk();
            if (eof) return -1;
        }
        int n = in.read(b, off, (int) Math.min(len, remaining));
        if (n < 0) throw incomplete();
        if (chunkHash != null) chunkHash.update(b, off, n);
        remaining -= n;
        decodedBytes += n;
        if (remaining == 0) endChunk();
        return n;
    }

    private void startChunk() throws IOException {
        String header = readLine();
        if (header == null) throw incomplete();
        String sizeText = header;
        chunkSignature = null;
        int semi = header.indexOf(';');
        if (semi >= 0) {
            sizeText = header.substring(0, semi);
            for (String ext : header.substring(semi + 1).split(";")) {
                int eq = ext.indexOf('=');
                if (eq > 0 && ext.substring(0, eq).trim().equals("chunk-signature")) {
                    chunkSignature = ext.substring(eq + 1).trim();
                }
            }
        }
        long size;
        try {
            size = Long.parseLong(sizeText.trim(), 16);
        } catch (NumberFormatException e) {
            throw new S3Exception(S3Error.INVALID_REQUEST, "Malformed aws-chunked chunk header");
        }
        if (size < 0 || size > MAX_CHUNK) throw new S3Exception(S3Error.INVALID_REQUEST, "Chunk size out of range");
        if (signedChunks && chunkSignature == null) {
            throw new S3Exception(S3Error.SIGNATURE_DOES_NOT_MATCH, "Chunk is missing its chunk-signature");
        }
        chunkHash = signedChunks ? SigV4.sha256() : null;
        remaining = size;
        if (size == 0) {
            verifyChunk();
            readTrailers();
            eof = true;
        }
    }

    private void endChunk() throws IOException {
        int cr = in.read();
        int lf = cr == '\r' ? in.read() : cr;
        if (lf != '\n') throw new S3Exception(S3Error.INVALID_REQUEST, "Malformed aws-chunked body: missing CRLF after chunk data");
        verifyChunk();
    }

    private void verifyChunk() {
        if (!signedChunks) return;
        String stringToSign = "AWS4-HMAC-SHA256-PAYLOAD\n" + auth.amzDate() + "\n" + auth.scope() + "\n"
                + previousSignature + "\n" + SigV4.EMPTY_SHA256 + "\n" + SigV4.hex(chunkHash.digest());
        String expected = SigV4.hmacHex(auth.signingKey(), stringToSign);
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                chunkSignature.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII))) {
            throw new S3Exception(S3Error.SIGNATURE_DOES_NOT_MATCH, "A chunk signature does not match");
        }
        previousSignature = expected;
    }

    private void readTrailers() throws IOException {
        StringBuilder canonical = new StringBuilder();
        String trailerSignature = null;
        while (true) {
            String line = readLine();
            if (line == null || line.isEmpty()) break;
            int colon = line.indexOf(':');
            if (colon <= 0) throw new S3Exception(S3Error.INVALID_REQUEST, "Malformed trailing header");
            String name = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = line.substring(colon + 1).trim();
            if (name.equals("x-amz-trailer-signature")) {
                trailerSignature = value;
            } else {
                trailers.put(name, value);
                canonical.append(name).append(':').append(value).append('\n');
            }
        }
        if (!expectTrailer && !trailers.isEmpty()) {
            throw new S3Exception(S3Error.INVALID_REQUEST, "Trailing headers sent without a -TRAILER payload mode");
        }
        if (signedChunks && expectTrailer) {
            if (trailerSignature == null) {
                throw new S3Exception(S3Error.SIGNATURE_DOES_NOT_MATCH, "Missing x-amz-trailer-signature");
            }
            String stringToSign = "AWS4-HMAC-SHA256-TRAILER\n" + auth.amzDate() + "\n" + auth.scope() + "\n"
                    + previousSignature + "\n" + SigV4.sha256Hex(canonical.toString());
            String expected = SigV4.hmacHex(auth.signingKey(), stringToSign);
            if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                    trailerSignature.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII))) {
                throw new S3Exception(S3Error.SIGNATURE_DOES_NOT_MATCH, "The trailer signature does not match");
            }
        }
    }

    /** Reads a line ending in \n or \r\n; null at end of stream with nothing read. */
    private String readLine() throws IOException {
        StringBuilder sb = new StringBuilder(80);
        while (true) {
            int c = in.read();
            if (c < 0) return sb.length() == 0 ? null : sb.toString();
            if (c == '\n') break;
            if (c != '\r') sb.append((char) c);
            if (sb.length() > MAX_LINE) throw new S3Exception(S3Error.INVALID_REQUEST, "aws-chunked line too long");
        }
        return sb.toString();
    }

    private static S3Exception incomplete() {
        return new S3Exception(S3Error.INCOMPLETE_BODY);
    }

    @Override
    public void close() throws IOException {
        in.close();
    }
}
