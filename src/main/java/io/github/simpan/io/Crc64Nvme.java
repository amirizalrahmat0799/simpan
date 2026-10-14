package io.github.simpan.io;

import java.util.zip.Checksum;

/**
 * CRC-64/NVME, the checksum Amazon S3 uses by default since 2025.
 *
 * <p>Parameters: polynomial 0xAD93D23594C93659, reflected input and output, initial value and final XOR all ones.
 * Check value for "123456789" is 0xAE8B14860A799888. Uses slicing-by-8 tables, so it processes eight bytes per step.
 */
public final class Crc64Nvme implements Checksum {

    private static final long POLY_REFLECTED = Long.reverse(0xAD93D23594C93659L);
    private static final long[][] TABLE = new long[8][256];

    static {
        for (int n = 0; n < 256; n++) {
            long crc = n;
            for (int k = 0; k < 8; k++) {
                crc = (crc & 1) != 0 ? (crc >>> 1) ^ POLY_REFLECTED : crc >>> 1;
            }
            TABLE[0][n] = crc;
        }
        for (int n = 0; n < 256; n++) {
            long crc = TABLE[0][n];
            for (int k = 1; k < 8; k++) {
                crc = TABLE[0][(int) (crc & 0xFF)] ^ (crc >>> 8);
                TABLE[k][n] = crc;
            }
        }
    }

    private long crc = ~0L;

    @Override
    public void update(int b) {
        crc = TABLE[0][(int) ((crc ^ b) & 0xFF)] ^ (crc >>> 8);
    }

    @Override
    public void update(byte[] b, int off, int len) {
        long c = crc;
        int i = off;
        int end = off + len;
        while (end - i >= 8) {
            c ^= (b[i] & 0xFFL) | (b[i + 1] & 0xFFL) << 8 | (b[i + 2] & 0xFFL) << 16 | (b[i + 3] & 0xFFL) << 24
                    | (b[i + 4] & 0xFFL) << 32 | (b[i + 5] & 0xFFL) << 40 | (b[i + 6] & 0xFFL) << 48
                    | (b[i + 7] & 0xFFL) << 56;
            c = TABLE[7][(int) (c & 0xFF)] ^ TABLE[6][(int) ((c >>> 8) & 0xFF)]
                    ^ TABLE[5][(int) ((c >>> 16) & 0xFF)] ^ TABLE[4][(int) ((c >>> 24) & 0xFF)]
                    ^ TABLE[3][(int) ((c >>> 32) & 0xFF)] ^ TABLE[2][(int) ((c >>> 40) & 0xFF)]
                    ^ TABLE[1][(int) ((c >>> 48) & 0xFF)] ^ TABLE[0][(int) (c >>> 56)];
            i += 8;
        }
        while (i < end) {
            c = TABLE[0][(int) ((c ^ b[i++]) & 0xFF)] ^ (c >>> 8);
        }
        crc = c;
    }

    @Override
    public long getValue() {
        return ~crc;
    }

    @Override
    public void reset() {
        crc = ~0L;
    }
}
