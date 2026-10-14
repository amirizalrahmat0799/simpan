package io.github.simpan.io;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.zip.CRC32;
import java.util.zip.CRC32C;
import java.util.zip.Checksum;

/** The additional checksums S3 clients can send with uploads (x-amz-checksum-*). */
public enum ChecksumAlgorithm {
    CRC32("crc32", true),
    CRC32C("crc32c", true),
    CRC64NVME("crc64nvme", true),
    SHA1("sha1", false),
    SHA256("sha256", false);

    private final String id;
    private final boolean crc;

    ChecksumAlgorithm(String id, boolean crc) {
        this.id = id;
        this.crc = crc;
    }

    /** Header name, for example {@code x-amz-checksum-crc32}. */
    public String header() {
        return "x-amz-checksum-" + id;
    }

    /** Name used in XML and in x-amz-sdk-checksum-algorithm, for example {@code CRC32C}. */
    public String apiName() {
        return name();
    }

    /** Element name in XML responses, for example {@code ChecksumCRC64NVME}. */
    public String xmlElement() {
        return "Checksum" + name();
    }

    /** CRCs can be combined into a checksum of the whole object; hashes only into a composite. */
    public boolean supportsFullObject() {
        return crc;
    }

    public static ChecksumAlgorithm fromApiName(String name) {
        if (name == null) return null;
        try {
            return valueOf(name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public static ChecksumAlgorithm fromHeader(String header) {
        for (ChecksumAlgorithm a : values()) if (a.header().equalsIgnoreCase(header)) return a;
        return null;
    }

    public Hasher newHasher() {
        return switch (this) {
            case CRC32 -> new CrcHasher(new CRC32(), 4);
            case CRC32C -> new CrcHasher(new CRC32C(), 4);
            case CRC64NVME -> new CrcHasher(new Crc64Nvme(), 8);
            case SHA1 -> new DigestHasher("SHA-1");
            case SHA256 -> new DigestHasher("SHA-256");
        };
    }

    /** A running checksum or hash that produces the raw big-endian bytes S3 base64-encodes. */
    public interface Hasher {
        void update(byte[] b, int off, int len);

        byte[] digest();
    }

    private static final class CrcHasher implements Hasher {
        private final Checksum c;
        private final int bytes;

        CrcHasher(Checksum c, int bytes) {
            this.c = c;
            this.bytes = bytes;
        }

        @Override
        public void update(byte[] b, int off, int len) {
            c.update(b, off, len);
        }

        @Override
        public byte[] digest() {
            byte[] full = ByteBuffer.allocate(8).putLong(c.getValue()).array();
            byte[] out = new byte[bytes];
            System.arraycopy(full, 8 - bytes, out, 0, bytes);
            return out;
        }
    }

    static final class DigestHasher implements Hasher {
        private final MessageDigest md;

        DigestHasher(String alg) {
            try {
                md = MessageDigest.getInstance(alg);
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public void update(byte[] b, int off, int len) {
            md.update(b, off, len);
        }

        @Override
        public byte[] digest() {
            return md.digest();
        }
    }
}
