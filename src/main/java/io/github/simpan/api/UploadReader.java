package io.github.simpan.api;

import io.github.simpan.auth.AuthResult;
import io.github.simpan.auth.SigV4;
import io.github.simpan.io.AwsChunkedInputStream;
import io.github.simpan.io.ChecksumAlgorithm;
import io.github.simpan.io.ChecksumAlgorithm.Hasher;
import io.github.simpan.storage.BlobStore;
import io.github.simpan.storage.StagedBlob;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

/**
 * Receives a request body into a staged file and proves it is intact before anything is committed: the aws-chunked
 * chunk signatures, x-amz-content-sha256, Content-MD5, the declared length, and the x-amz-checksum-* value sent as a
 * header or as a trailer. Any mismatch discards the staged file.
 */
final class UploadReader {

    /**
     * The verified upload.
     *
     * @param checksum raw bytes of the checksum in {@code algorithm} (the algorithm the object is stored with)
     */
    record Upload(StagedBlob staged, ChecksumAlgorithm algorithm, byte[] checksum, ChecksumAlgorithm clientAlgorithm,
            byte[] clientChecksum) {}

    private final BlobStore blobs;

    UploadReader(BlobStore blobs) {
        this.blobs = blobs;
    }

    /**
     * @param storeAlgorithm algorithm to store the checksum in, or null to use the client's (CRC64NVME if none)
     */
    Upload read(S3Request req, long maxBytes, ChecksumAlgorithm storeAlgorithm) throws IOException {
        AuthResult auth = req.auth();
        InputStream raw = req.body();
        AwsChunkedInputStream chunked = auth.streaming() ? new AwsChunkedInputStream(raw, auth) : null;
        InputStream in = chunked != null ? chunked : raw;

        ChecksumAlgorithm clientAlg = null;
        String expectedHeaderValue = null;
        String trailer = req.header("x-amz-trailer");
        if (trailer != null && auth.trailer()) {
            clientAlg = ChecksumAlgorithm.fromHeader(trailer.trim());
            if (clientAlg == null) throw new S3Exception(S3Error.INVALID_REQUEST, "Unsupported trailer " + trailer);
        } else {
            for (ChecksumAlgorithm a : ChecksumAlgorithm.values()) {
                String v = req.header(a.header());
                if (v != null) {
                    if (clientAlg != null) {
                        throw new S3Exception(S3Error.INVALID_REQUEST, "Expecting a single x-amz-checksum- header");
                    }
                    clientAlg = a;
                    expectedHeaderValue = v.trim();
                }
            }
            if (clientAlg == null) clientAlg = ChecksumAlgorithm.fromApiName(req.header("x-amz-sdk-checksum-algorithm"));
        }
        ChecksumAlgorithm storeAlg = storeAlgorithm != null ? storeAlgorithm
                : clientAlg != null ? clientAlg : ChecksumAlgorithm.CRC64NVME;

        List<Hasher> hashers = new ArrayList<>();
        Hasher store = storeAlg.newHasher();
        hashers.add(store);
        Hasher client = null;
        if (clientAlg != null && clientAlg != storeAlg) {
            client = clientAlg.newHasher();
            hashers.add(client);
        }
        String expectedSha = chunked == null ? auth.expectedSha256() : null;
        MessageDigest sha = expectedSha != null ? SigV4.sha256() : null;
        if (sha != null) {
            hashers.add(new Hasher() {
                public void update(byte[] b, int off, int len) {
                    sha.update(b, off, len);
                }

                public byte[] digest() {
                    return sha.digest();
                }
            });
        }

        StagedBlob staged = blobs.stage(in, maxBytes, hashers);
        try {
            if (chunked != null) {
                String decoded = req.header("x-amz-decoded-content-length");
                if (decoded != null && !decoded.trim().equals(String.valueOf(staged.size()))) {
                    throw new S3Exception(S3Error.INCOMPLETE_BODY);
                }
            } else if (req.contentLength() >= 0 && req.contentLength() != staged.size()) {
                throw new S3Exception(S3Error.INCOMPLETE_BODY);
            }
            if (sha != null && !SigV4.hex(sha.digest()).equals(expectedSha)) {
                throw new S3Exception(S3Error.CONTENT_SHA256_MISMATCH).with("ClientComputedContentSHA256", expectedSha);
            }
            String md5Header = req.header("content-md5");
            if (md5Header != null) {
                byte[] expected;
                try {
                    expected = Base64.getDecoder().decode(md5Header.trim());
                } catch (IllegalArgumentException e) {
                    throw new S3Exception(S3Error.INVALID_DIGEST);
                }
                if (expected.length != 16) throw new S3Exception(S3Error.INVALID_DIGEST);
                if (!MessageDigest.isEqual(expected, staged.md5())) throw new S3Exception(S3Error.BAD_DIGEST);
            }
            byte[] storeSum = store.digest();
            byte[] clientSum = client != null ? client.digest() : clientAlg == storeAlg ? storeSum : null;
            if (clientAlg != null) {
                String expected = expectedHeaderValue;
                if (expected == null && chunked != null) expected = chunked.trailers().get(clientAlg.header());
                if (expected != null) {
                    byte[] exp;
                    try {
                        exp = Base64.getDecoder().decode(expected);
                    } catch (IllegalArgumentException e) {
                        throw new S3Exception(S3Error.INVALID_REQUEST, "Value for " + clientAlg.header() + " header is invalid.");
                    }
                    if (!MessageDigest.isEqual(exp, clientSum)) {
                        throw new S3Exception(S3Error.BAD_DIGEST, "The " + clientAlg.apiName().toLowerCase(Locale.ROOT)
                                + " you specified did not match the calculated checksum.");
                    }
                } else if (trailer != null) {
                    throw new S3Exception(S3Error.INVALID_REQUEST, "The trailer " + trailer + " was declared but not sent");
                }
            }
            return new Upload(staged, storeAlg, storeSum, clientAlg, clientSum);
        } catch (RuntimeException e) {
            blobs.discard(staged);
            throw e;
        }
    }

    /** Reads a small body (XML documents) fully, with the same integrity checks. */
    byte[] readSmall(S3Request req, int maxBytes) throws IOException {
        Upload u = read(req, maxBytes, null);
        try {
            return java.nio.file.Files.readAllBytes(u.staged().file());
        } finally {
            blobs.discard(u.staged());
        }
    }
}
