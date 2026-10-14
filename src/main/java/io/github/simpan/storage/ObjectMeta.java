package io.github.simpan.storage;

import java.util.List;
import java.util.Map;

/**
 * Everything stored about an object except its bytes.
 *
 * @param etag          quoted S3 ETag: MD5 of the data, or MD5 of the part MD5s plus "-N" for multipart objects
 * @param headers       content headers to return on GET (Content-Type, Content-Encoding, Cache-Control, ...)
 * @param userMetadata  x-amz-meta-* values, keys without the prefix
 * @param checksumType  FULL_OBJECT or COMPOSITE
 * @param partsCount    0 for a single PUT, else the number of parts
 */
public record ObjectMeta(
        String key,
        long size,
        String etag,
        long lastModified,
        Map<String, String> headers,
        Map<String, String> userMetadata,
        String checksumAlgorithm,
        String checksum,
        String checksumType,
        List<Segment> segments,
        int partsCount) {

    public ObjectMeta {
        headers = headers == null ? Map.of() : Map.copyOf(headers);
        userMetadata = userMetadata == null ? Map.of() : Map.copyOf(userMetadata);
        segments = segments == null ? List.of() : List.copyOf(segments);
    }

    public String contentType() {
        return headers.getOrDefault("Content-Type", "binary/octet-stream");
    }

    public ObjectMeta withKey(String newKey, List<Segment> newSegments, long now) {
        return new ObjectMeta(newKey, size, etag, now, headers, userMetadata, checksumAlgorithm, checksum,
                checksumType, newSegments, partsCount);
    }
}
