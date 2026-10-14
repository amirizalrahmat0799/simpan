package io.github.simpan.storage;

/**
 * An uploaded part.
 *
 * @param md5       raw MD5 hex of the part (its ETag without quotes)
 * @param checksum  base64 checksum in the upload's algorithm
 */
public record PartMeta(int partNumber, long size, String md5, long lastModified, String checksum) {
    public String etag() {
        return "\"" + md5 + "\"";
    }
}
