package io.github.simpan.storage;

/**
 * An upload in progress, persisted as {@code multipart/<uploadId>/upload.json}.
 *
 * @param checksumAlgorithm algorithm for part and object checksums (CRC64NVME when the client names none)
 * @param checksumType      FULL_OBJECT or COMPOSITE
 */
public record MultipartUpload(
        String uploadId, String bucket, String key, long initiated, ObjectAttributes attributes,
        String checksumAlgorithm, String checksumType) {}
