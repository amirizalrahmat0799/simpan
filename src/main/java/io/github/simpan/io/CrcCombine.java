package io.github.simpan.io;

/**
 * Combines the CRCs of two adjacent byte ranges into the CRC of their concatenation, without reading the data:
 * crc(A + B) = crc(A) * x^(8 * len(B)) mod P  xor  crc(B). This is zlib's crc32_combine method generalised to any
 * reflected CRC whose initial value and final XOR are all ones (CRC-32, CRC-32C, CRC-64/NVME).
 *
 * <p>It lets a multipart upload's full-object checksum be computed from the part checksums in O(parts) time.
 */
public final class CrcCombine {

    private final long poly;
    private final int width;
    private final long[] x2n;

    private CrcCombine(long normalPoly, int width) {
        this.width = width;
        this.poly = Long.reverse(normalPoly) >>> (64 - width);
        this.x2n = new long[width];
        long p = 1L << (width - 2); // x^1
        x2n[0] = p;
        for (int n = 1; n < width; n++) x2n[n] = p = multModP(p, p);
    }

    public static final CrcCombine CRC32 = new CrcCombine(0x04C11DB7L, 32);
    public static final CrcCombine CRC32C = new CrcCombine(0x1EDC6F41L, 32);
    public static final CrcCombine CRC64NVME = new CrcCombine(0xAD93D23594C93659L, 64);

    public static CrcCombine of(ChecksumAlgorithm alg) {
        return switch (alg) {
            case CRC32 -> CRC32;
            case CRC32C -> CRC32C;
            case CRC64NVME -> CRC64NVME;
            default -> throw new IllegalArgumentException(alg + " is not a CRC");
        };
    }

    /** The CRC of A followed by B, given crc(A), crc(B) and the length of B in bytes. */
    public long combine(long crcA, long crcB, long lengthB) {
        return multModP(x2nModP(lengthB, 3), crcA) ^ crcB;
    }

    /** Combines big-endian CRC bytes (as S3 stores them). */
    public byte[] combine(byte[] crcA, byte[] crcB, long lengthB) {
        return toBytes(combine(fromBytes(crcA), fromBytes(crcB), lengthB));
    }

    public byte[] toBytes(long v) {
        int n = width / 8;
        byte[] out = new byte[n];
        for (int i = n - 1; i >= 0; i--) {
            out[i] = (byte) v;
            v >>>= 8;
        }
        return out;
    }

    public long fromBytes(byte[] b) {
        long v = 0;
        for (byte x : b) v = (v << 8) | (x & 0xFF);
        return v;
    }

    private long multModP(long a, long b) {
        long m = 1L << (width - 1);
        long p = 0;
        while (true) {
            if ((a & m) != 0) {
                p ^= b;
                if ((a & (m - 1)) == 0) break;
            }
            m >>>= 1;
            b = (b & 1) != 0 ? (b >>> 1) ^ poly : b >>> 1;
        }
        return p;
    }

    /** x^(n * 2^k) mod P. */
    private long x2nModP(long n, int k) {
        long p = 1L << (width - 1); // x^0
        while (n != 0) {
            if ((n & 1) != 0) p = multModP(x2n[k % width], p);
            n >>>= 1;
            k++;
        }
        return p;
    }
}
