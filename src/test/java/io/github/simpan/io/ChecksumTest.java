package io.github.simpan.io;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class ChecksumTest {

    static byte[] digest(ChecksumAlgorithm alg, byte[] data) {
        ChecksumAlgorithm.Hasher h = alg.newHasher();
        h.update(data, 0, data.length);
        return h.digest();
    }

    @Test
    void standardCheckValues() {
        byte[] check = "123456789".getBytes(StandardCharsets.US_ASCII);
        assertThat(HexFormat.of().formatHex(digest(ChecksumAlgorithm.CRC64NVME, check))).isEqualTo("ae8b14860a799888");
        assertThat(HexFormat.of().formatHex(digest(ChecksumAlgorithm.CRC32, check))).isEqualTo("cbf43926");
        assertThat(HexFormat.of().formatHex(digest(ChecksumAlgorithm.CRC32C, check))).isEqualTo("e3069283");
    }

    @Test
    void sliceBy8MatchesByteAtATime() {
        byte[] data = new byte[100_003];
        new Random(7).nextBytes(data);
        Crc64Nvme fast = new Crc64Nvme();
        fast.update(data, 0, data.length);
        Crc64Nvme slow = new Crc64Nvme();
        for (byte b : data) slow.update(b);
        assertThat(fast.getValue()).isEqualTo(slow.getValue());
    }

    @Test
    void s3DocumentationCrc32c() {
        byte[] a = new byte[66560];
        java.util.Arrays.fill(a, (byte) 'a');
        assertThat(Base64.getEncoder().encodeToString(digest(ChecksumAlgorithm.CRC32C, a))).isEqualTo("sOO8/Q==");
    }

    @ParameterizedTest
    @EnumSource(value = ChecksumAlgorithm.class, names = {"CRC32", "CRC32C", "CRC64NVME"})
    void combiningPartCrcsEqualsTheCrcOfTheWhole(ChecksumAlgorithm alg) {
        Random r = new Random(alg.ordinal());
        for (int i = 0; i < 100; i++) {
            byte[] a = new byte[r.nextInt(5000)];
            byte[] b = new byte[r.nextInt(70_000)];
            r.nextBytes(a);
            r.nextBytes(b);
            byte[] whole = new byte[a.length + b.length];
            System.arraycopy(a, 0, whole, 0, a.length);
            System.arraycopy(b, 0, whole, a.length, b.length);
            assertThat(CrcCombine.of(alg).combine(digest(alg, a), digest(alg, b), b.length)).isEqualTo(digest(alg, whole));
        }
    }
}
