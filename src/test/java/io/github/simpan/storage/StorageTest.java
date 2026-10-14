package io.github.simpan.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.simpan.api.S3Error;
import io.github.simpan.api.S3Exception;
import io.github.simpan.io.ChecksumAlgorithm;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StorageTest {

    @TempDir
    Path root;
    Storage storage;

    @BeforeEach
    void open() {
        storage = newStorage(1000);
        storage.createBucket("b");
    }

    @AfterEach
    void close() throws IOException {
        storage.close();
    }

    Storage newStorage(long compactAfter) {
        Storage s = new Storage(root, false, compactAfter, Clock.systemUTC());
        s.open();
        return s;
    }

    ObjectMeta put(Storage s, String key, String body) {
        return put(s, key, body, Precondition.NONE);
    }

    ObjectMeta put(Storage s, String key, String body, Precondition pre) {
        ChecksumAlgorithm.Hasher h = ChecksumAlgorithm.CRC64NVME.newHasher();
        StagedBlob staged = s.blobs().stage(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)), 1 << 20, List.of(h));
        return s.putObject("b", key, staged, new ObjectAttributes(Map.of("Content-Type", "text/plain"), Map.of("k", "v")),
                ChecksumAlgorithm.CRC64NVME, h.digest(), pre);
    }

    String read(Storage s, String key) throws IOException {
        ObjectMeta m = s.get("b", key);
        try (InputStream in = s.read(m, 0, m.size())) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void putGetOverwriteDelete() throws IOException {
        ObjectMeta m = put(storage, "a.txt", "hello");
        assertThat(m.etag()).isEqualTo("\"5d41402abc4b2a76b9719d911017c592\"");
        assertThat(read(storage, "a.txt")).isEqualTo("hello");
        put(storage, "a.txt", "bye");
        assertThat(read(storage, "a.txt")).isEqualTo("bye");
        assertThat(blobCount()).isEqualTo(1);
        assertThat(storage.objectCount()).isEqualTo(1);
        assertThat(storage.bytesStored()).isEqualTo(3);
        assertThat(storage.delete("b", "a.txt")).isNotNull();
        assertThat(storage.get("b", "a.txt")).isNull();
        assertThat(blobCount()).isZero();
    }

    @Test
    void conditionalWrites() {
        put(storage, "k", "1", new Precondition(true, null));
        assertThatThrownBy(() -> put(storage, "k", "2", new Precondition(true, null)))
                .isInstanceOfSatisfying(S3Exception.class, e -> assertThat(e.error()).isEqualTo(S3Error.PRECONDITION_FAILED));
        assertThatThrownBy(() -> put(storage, "k", "2", new Precondition(false, "\"nope\"")))
                .isInstanceOfSatisfying(S3Exception.class, e -> assertThat(e.error()).isEqualTo(S3Error.PRECONDITION_FAILED));
        String etag = storage.get("b", "k").etag();
        put(storage, "k", "3", new Precondition(false, etag));
        assertThat(storage.get("b", "k").size()).isEqualTo(1);
        assertThat(blobCount()).as("rejected writes leave no blobs behind").isEqualTo(1);
    }

    @Test
    void listingWithPrefixDelimiterAndPagination() {
        for (String k : List.of("a/1", "a/2", "b/1", "b/2/x", "c", "d/1")) put(storage, k, k);
        ListPage all = storage.list("b", "", "/", null, false, 1000);
        assertThat(all.commonPrefixes()).containsExactly("a/", "b/", "d/");
        assertThat(all.objects()).extracting(ObjectMeta::key).containsExactly("c");

        List<String> seen = new ArrayList<>();
        String after = null;
        boolean afterIsPrefix = false;
        while (true) {
            ListPage p = storage.list("b", "", "/", after, afterIsPrefix, 1);
            seen.addAll(p.commonPrefixes());
            p.objects().forEach(o -> seen.add(o.key()));
            if (!p.truncated()) break;
            after = p.last();
            afterIsPrefix = p.lastIsPrefix();
        }
        assertThat(seen).containsExactly("a/", "b/", "c", "d/");

        ListPage b = storage.list("b", "b/", "/", null, false, 1000);
        assertThat(b.objects()).extracting(ObjectMeta::key).containsExactly("b/1");
        assertThat(b.commonPrefixes()).containsExactly("b/2/");
        assertThat(storage.list("b", "a/", null, "a/1", false, 1000).objects()).extracting(ObjectMeta::key).containsExactly("a/2");
    }

    @Test
    void keysSortInUtf8ByteOrder() {
        // U+FF21 is a single UTF-16 unit; U+1F600 is a surrogate pair. UTF-8 bytes put the emoji last.
        for (String k : List.of("Ａ", "😀", "z")) put(storage, k, k);
        assertThat(storage.list("b", "", null, null, false, 10).objects()).extracting(ObjectMeta::key)
                .containsExactly("z", "Ａ", "😀");
        assertThat(KeyOrder.prefixEnd("ab")).isEqualTo("ac");
    }

    @Test
    void recoversFromTheLogAfterRestart() throws IOException {
        put(storage, "x", "one");
        put(storage, "y", "two");
        storage.delete("b", "y");
        storage.createBucket("c");
        storage.close();
        storage = newStorage(1000);
        assertThat(read(storage, "x")).isEqualTo("one");
        assertThat(storage.get("b", "y")).isNull();
        assertThat(storage.bucket("c")).isNotNull();
        assertThat(storage.objectCount()).isEqualTo(1);
    }

    @Test
    void tornLastRecordIsDiscardedOnRecovery() throws IOException {
        put(storage, "kept", "ok");
        storage.close();
        Path log = root.resolve("meta/journal-0.log");
        Files.writeString(log, "deadbeef\t{\"op\":\"put\",\"b\":\"b\",\"o\":{\"key\":\"half", StandardOpenOption.APPEND);
        long tornSize = Files.size(log);
        storage = newStorage(1000);
        assertThat(read(storage, "kept")).isEqualTo("ok");
        assertThat(Files.size(log)).isLessThan(tornSize);
        put(storage, "after", "fine");
        storage.close();
        storage = newStorage(1000);
        assertThat(read(storage, "after")).isEqualTo("fine");
    }

    @Test
    void compactionKeepsEverything() throws IOException {
        storage.close();
        storage = newStorage(5);
        for (int i = 0; i < 23; i++) put(storage, "k" + i, "v" + i);
        storage.delete("b", "k3");
        storage.close();
        try (Stream<Path> files = Files.list(root.resolve("meta"))) {
            assertThat(files.map(p -> p.getFileName().toString())).anyMatch(n -> n.startsWith("snapshot-"));
        }
        storage = newStorage(5);
        assertThat(storage.objectCount()).isEqualTo(22);
        assertThat(read(storage, "k22")).isEqualTo("v22");
        assertThat(storage.get("b", "k3")).isNull();
    }

    @Test
    void orphanedBlobsAreCollectedAtStartup() throws IOException {
        put(storage, "real", "data");
        StagedBlob orphan = storage.blobs().stage(new ByteArrayInputStream(new byte[10]), 100, List.of());
        storage.blobs().adopt(orphan);
        storage.blobs().stage(new ByteArrayInputStream(new byte[10]), 100, List.of());
        assertThat(blobCount()).isEqualTo(2);
        storage.close();
        storage = newStorage(1000);
        assertThat(blobCount()).isEqualTo(1);
        try (Stream<Path> tmp = Files.list(root.resolve("tmp"))) {
            assertThat(tmp).isEmpty();
        }
        assertThat(read(storage, "real")).isEqualTo("data");
    }

    @Test
    void multipartUploadSurvivesRestartAndCompletes() throws Exception {
        MultipartUpload u = storage.createUpload("b", "big", ObjectAttributes.empty(), ChecksumAlgorithm.CRC32, "FULL_OBJECT");
        byte[] p1 = "first part ".getBytes(StandardCharsets.UTF_8);
        byte[] p2 = "second part".getBytes(StandardCharsets.UTF_8);
        part(u, 1, p1);
        part(u, 2, p2);
        storage.close();
        storage = newStorage(1000);
        MultipartUpload resumed = storage.upload(u.uploadId(), "b", "big");
        assertThat(storage.parts(resumed)).hasSize(2);
        ObjectMeta m = storage.completeUpload(resumed, storage.parts(resumed), Precondition.NONE);
        MessageDigest md5 = MessageDigest.getInstance("MD5");
        md5.update(MessageDigest.getInstance("MD5").digest(p1));
        md5.update(MessageDigest.getInstance("MD5").digest(p2));
        assertThat(m.etag()).isEqualTo("\"" + HexFormat.of().formatHex(md5.digest()) + "-2\"");
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(p1);
        crc.update(p2);
        assertThat(java.util.Base64.getDecoder().decode(m.checksum()))
                .isEqualTo(java.nio.ByteBuffer.allocate(4).putInt((int) crc.getValue()).array());
        assertThat(read(storage, "big")).isEqualTo("first part second part");
        assertThatThrownBy(() -> storage.upload(u.uploadId(), "b", "big"))
                .isInstanceOfSatisfying(S3Exception.class, e -> assertThat(e.error()).isEqualTo(S3Error.NO_SUCH_UPLOAD));
        assertThat(Files.exists(root.resolve("multipart").resolve(u.uploadId()))).isFalse();
    }

    @Test
    void bucketRules() {
        assertThatThrownBy(() -> storage.createBucket("b"))
                .isInstanceOfSatisfying(S3Exception.class, e -> assertThat(e.error()).isEqualTo(S3Error.BUCKET_ALREADY_OWNED_BY_YOU));
        put(storage, "x", "1");
        assertThatThrownBy(() -> storage.deleteBucket("b"))
                .isInstanceOfSatisfying(S3Exception.class, e -> assertThat(e.error()).isEqualTo(S3Error.BUCKET_NOT_EMPTY));
        storage.delete("b", "x");
        storage.deleteBucket("b");
        assertThat(storage.buckets()).isEmpty();
        assertThatThrownBy(() -> storage.get("b", "x"))
                .isInstanceOfSatisfying(S3Exception.class, e -> assertThat(e.error()).isEqualTo(S3Error.NO_SUCH_BUCKET));
    }

    private void part(MultipartUpload u, int n, byte[] data) {
        ChecksumAlgorithm.Hasher h = ChecksumAlgorithm.CRC32.newHasher();
        StagedBlob s = storage.blobs().stage(new ByteArrayInputStream(data), 1 << 20, List.of(h));
        storage.putPart(u, n, s, h.digest());
    }

    private long blobCount() {
        try (Stream<Path> walk = Files.walk(root.resolve("data"))) {
            return walk.filter(Files::isRegularFile).count();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
