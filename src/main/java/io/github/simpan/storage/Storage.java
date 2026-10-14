package io.github.simpan.storage;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.simpan.api.S3Error;
import io.github.simpan.api.S3Exception;
import io.github.simpan.io.ChecksumAlgorithm;
import io.github.simpan.io.CrcCombine;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The storage engine: object bytes in immutable blob files, metadata in an in-memory sorted index made durable by
 * the {@link Journal}.
 *
 * <p>A write is: stage bytes to a temporary file, verify them, move the file into the blob store, then, holding the
 * metadata lock, check preconditions, append to the journal and update the index. Readers never take the lock: they
 * see either the old object or the new one, never a mix. Blobs replaced by a write are deleted after it commits.
 */
public final class Storage implements Closeable {

    private static final Logger log = LoggerFactory.getLogger(Storage.class);
    private static final HexFormat HEX = HexFormat.of();

    private final Path root;
    private final Path multipartDir;
    private final BlobStore blobs;
    private final Journal journal;
    private final ObjectMapper json;
    private final Clock clock;
    private final long compactAfter;
    private final ReentrantLock lock = new ReentrantLock();

    private final Map<String, BucketState> buckets = new ConcurrentSkipListMap<>();
    private final Map<String, MultipartUpload> uploads = new ConcurrentHashMap<>();
    private final Map<String, ConcurrentSkipListMap<Integer, PartMeta>> parts = new ConcurrentHashMap<>();
    private final AtomicLong objectCount = new AtomicLong();
    private final AtomicLong bytesStored = new AtomicLong();

    public Storage(Path root, boolean fsync, long compactAfter, Clock clock) {
        this.root = root;
        this.multipartDir = root.resolve("multipart");
        this.blobs = new BlobStore(root, fsync);
        this.json = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        this.journal = new Journal(root, fsync, json);
        this.clock = clock;
        this.compactAfter = compactAfter;
    }

    /** Loads metadata, resumes multipart uploads, and removes blobs left behind by a crash. */
    public void open() {
        try {
            long replayed = journal.load(buckets);
            loadUploads();
            Set<String> referenced = new HashSet<>();
            for (BucketState b : buckets.values()) {
                for (ObjectMeta m : b.objects().values()) {
                    for (Segment s : m.segments()) referenced.add(s.blob());
                    objectCount.incrementAndGet();
                    bytesStored.addAndGet(m.size());
                }
            }
            int removed = blobs.collectGarbage(referenced);
            log.info("Storage at {}: {} buckets, {} objects, {} bytes; replayed {} log records, {} in-progress uploads, "
                    + "removed {} orphaned files", root.toAbsolutePath(), buckets.size(), objectCount.get(),
                    bytesStored.get(), replayed, uploads.size(), removed);
            if (journal.records() >= compactAfter) compact();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not open storage at " + root, e);
        }
    }

    public BlobStore blobs() {
        return blobs;
    }

    public long objectCount() {
        return objectCount.get();
    }

    public long bytesStored() {
        return bytesStored.get();
    }

    // ------------------------------------------------------------------ buckets

    public List<BucketInfo> buckets() {
        return buckets.values().stream().map(BucketState::info).toList();
    }

    public BucketInfo bucket(String name) {
        BucketState b = buckets.get(name);
        return b == null ? null : b.info();
    }

    public BucketInfo createBucket(String name) {
        lock.lock();
        try {
            if (buckets.containsKey(name)) throw new S3Exception(S3Error.BUCKET_ALREADY_OWNED_BY_YOU).with("BucketName", name);
            BucketInfo info = new BucketInfo(name, clock.millis());
            ObjectNode r = json.createObjectNode().put("op", "mkb").put("b", name).put("at", info.createdAt());
            journal.append(r);
            buckets.put(name, new BucketState(info, new ConcurrentSkipListMap<>(KeyOrder.INSTANCE)));
            return info;
        } finally {
            lock.unlock();
        }
    }

    public void deleteBucket(String name) {
        lock.lock();
        try {
            BucketState b = requireBucket(name);
            if (!b.objects().isEmpty()) throw new S3Exception(S3Error.BUCKET_NOT_EMPTY).with("BucketName", name);
            journal.append(json.createObjectNode().put("op", "rmb").put("b", name));
            buckets.remove(name);
        } finally {
            lock.unlock();
        }
        for (MultipartUpload u : List.copyOf(uploads.values())) if (u.bucket().equals(name)) abortUpload(u.uploadId());
    }

    private BucketState requireBucket(String name) {
        BucketState b = buckets.get(name);
        if (b == null) throw new S3Exception(S3Error.NO_SUCH_BUCKET).with("BucketName", name);
        return b;
    }

    // ------------------------------------------------------------------ objects

    /** The object's metadata; throws NoSuchBucket, returns null if the key does not exist. */
    public ObjectMeta get(String bucket, String key) {
        return requireBucket(bucket).objects().get(key);
    }

    public ObjectMeta putObject(String bucket, String key, StagedBlob staged, ObjectAttributes attrs,
            ChecksumAlgorithm alg, byte[] checksum, Precondition pre) {
        requireBucket(bucket);
        String blob = blobs.adopt(staged);
        ObjectMeta meta = new ObjectMeta(key, staged.size(), "\"" + HEX.formatHex(staged.md5()) + "\"",
                clock.millis(), attrs.headers(), attrs.userMetadata(), alg.apiName(),
                Base64.getEncoder().encodeToString(checksum), "FULL_OBJECT", List.of(new Segment(blob, staged.size())), 0);
        return commit(bucket, meta, pre);
    }

    /** Copies an object's bytes to a new key (new blob files, so the copies are independent). */
    public ObjectMeta copyObject(ObjectMeta source, String bucket, String key, ObjectAttributes replaceAttrs,
            Precondition pre) {
        requireBucket(bucket);
        List<Segment> copies = new ArrayList<>();
        for (Segment s : source.segments()) copies.add(new Segment(blobs.adoptCopy(blobs.path(s.blob())), s.size()));
        ObjectMeta meta = source.withKey(key, copies, clock.millis());
        if (replaceAttrs != null) {
            meta = new ObjectMeta(key, meta.size(), meta.etag(), meta.lastModified(), replaceAttrs.headers(),
                    replaceAttrs.userMetadata(), meta.checksumAlgorithm(), meta.checksum(), meta.checksumType(),
                    meta.segments(), meta.partsCount());
        }
        return commit(bucket, meta, pre);
    }

    /** Deletes an object; returns its metadata, or null if it did not exist. */
    public ObjectMeta delete(String bucket, String key) {
        ObjectMeta removed;
        lock.lock();
        try {
            BucketState b = requireBucket(bucket);
            removed = b.objects().get(key);
            if (removed == null) return null;
            journal.append(json.createObjectNode().put("op", "del").put("b", bucket).put("k", key));
            b.objects().remove(key);
            objectCount.decrementAndGet();
            bytesStored.addAndGet(-removed.size());
            maybeCompact();
        } finally {
            lock.unlock();
        }
        for (Segment s : removed.segments()) blobs.delete(s.blob());
        return removed;
    }

    public InputStream read(ObjectMeta meta, long offset, long length) throws IOException {
        return blobs.open(meta.segments(), offset, length);
    }

    private ObjectMeta commit(String bucket, ObjectMeta meta, Precondition pre) {
        ObjectMeta previous;
        lock.lock();
        try {
            BucketState b = buckets.get(bucket);
            if (b == null) {
                discardSegments(meta);
                throw new S3Exception(S3Error.NO_SUCH_BUCKET).with("BucketName", bucket);
            }
            previous = b.objects().get(meta.key());
            if (pre.ifNoneMatchAny() && previous != null) {
                discardSegments(meta);
                throw new S3Exception(S3Error.PRECONDITION_FAILED).with("Condition", "If-None-Match");
            }
            if (pre.ifMatch() != null) {
                if (previous == null) {
                    discardSegments(meta);
                    throw new S3Exception(S3Error.NO_SUCH_KEY).with("Key", meta.key());
                }
                if (!etagMatches(pre.ifMatch(), previous.etag())) {
                    discardSegments(meta);
                    throw new S3Exception(S3Error.PRECONDITION_FAILED).with("Condition", "If-Match");
                }
            }
            ObjectNode r = json.createObjectNode().put("op", "put").put("b", bucket);
            r.set("o", json.valueToTree(meta));
            journal.append(r);
            b.objects().put(meta.key(), meta);
            if (previous == null) {
                objectCount.incrementAndGet();
                bytesStored.addAndGet(meta.size());
            } else {
                bytesStored.addAndGet(meta.size() - previous.size());
            }
            maybeCompact();
        } catch (RuntimeException e) {
            if (!(e instanceof S3Exception)) discardSegments(meta);
            throw e;
        } finally {
            lock.unlock();
        }
        if (previous != null) for (Segment s : previous.segments()) blobs.delete(s.blob());
        return meta;
    }

    private void discardSegments(ObjectMeta meta) {
        for (Segment s : meta.segments()) blobs.delete(s.blob());
    }

    /** Compares ETags in If-Match / If-None-Match style: quotes optional, "*" matches anything. */
    public static boolean etagMatches(String condition, String etag) {
        for (String c : condition.split(",")) {
            String t = c.trim();
            if (t.startsWith("W/")) t = t.substring(2);
            if (t.equals("*")) return true;
            if (t.replace("\"", "").equals(etag.replace("\"", ""))) return true;
        }
        return false;
    }

    /**
     * Lists keys after {@code after} (exclusive; a key, or a common prefix when {@code afterIsPrefix}), grouping keys
     * that contain {@code delimiter} after {@code prefix} into common prefixes.
     */
    public ListPage list(String bucket, String prefix, String delimiter, String after, boolean afterIsPrefix,
            int maxKeys) {
        ConcurrentSkipListMap<String, ObjectMeta> objects = requireBucket(bucket).objects();
        String pfx = prefix == null ? "" : prefix;
        String delim = delimiter == null || delimiter.isEmpty() ? null : delimiter;
        List<ObjectMeta> out = new ArrayList<>();
        List<String> prefixes = new ArrayList<>();
        String last = null;
        boolean lastIsPrefix = false;

        String cursor;
        boolean inclusive;
        if (after != null && KeyOrder.INSTANCE.compare(after, pfx) >= 0) {
            cursor = afterIsPrefix ? KeyOrder.prefixEnd(after) : after;
            inclusive = afterIsPrefix;
            if (cursor == null) return new ListPage(out, prefixes, false, null, false);
        } else {
            cursor = pfx;
            inclusive = true;
        }
        int count = 0;
        while (true) {
            Map.Entry<String, ObjectMeta> e = inclusive ? objects.ceilingEntry(cursor) : objects.higherEntry(cursor);
            if (e == null) return new ListPage(out, prefixes, false, last, lastIsPrefix);
            String key = e.getKey();
            if (!key.startsWith(pfx)) return new ListPage(out, prefixes, false, last, lastIsPrefix);
            if (count == maxKeys) return new ListPage(out, prefixes, true, last, lastIsPrefix);
            int d = delim == null ? -1 : key.indexOf(delim, pfx.length());
            if (d >= 0) {
                String common = key.substring(0, d + delim.length());
                prefixes.add(common);
                last = common;
                lastIsPrefix = true;
                count++;
                cursor = KeyOrder.prefixEnd(common);
                inclusive = true;
                if (cursor == null) return new ListPage(out, prefixes, false, last, true);
            } else {
                out.add(e.getValue());
                last = key;
                lastIsPrefix = false;
                count++;
                cursor = key;
                inclusive = false;
            }
        }
    }

    // ------------------------------------------------------------------ multipart

    public MultipartUpload createUpload(String bucket, String key, ObjectAttributes attrs, ChecksumAlgorithm alg,
            String checksumType) {
        requireBucket(bucket);
        String id = BlobStore.newId() + BlobStore.newId();
        MultipartUpload u = new MultipartUpload(id, bucket, key, clock.millis(), attrs, alg.apiName(), checksumType);
        try {
            Path dir = multipartDir.resolve(id);
            Files.createDirectories(dir);
            writeJsonAtomically(dir.resolve("upload.json"), u);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        uploads.put(id, u);
        parts.put(id, new ConcurrentSkipListMap<>());
        return u;
    }

    /** The upload, checked against the bucket and key in the request. */
    public MultipartUpload upload(String uploadId, String bucket, String key) {
        MultipartUpload u = uploadId == null ? null : uploads.get(uploadId);
        if (u == null || !u.bucket().equals(bucket) || !u.key().equals(key)) {
            throw new S3Exception(S3Error.NO_SUCH_UPLOAD).with("UploadId", uploadId);
        }
        return u;
    }

    public PartMeta putPart(MultipartUpload u, int partNumber, StagedBlob staged, byte[] checksum) {
        Path dir = multipartDir.resolve(u.uploadId());
        PartMeta p = new PartMeta(partNumber, staged.size(), HEX.formatHex(staged.md5()), clock.millis(),
                Base64.getEncoder().encodeToString(checksum));
        try {
            move(staged.file(), dir.resolve(partNumber + ".part"));
            writeJsonAtomically(dir.resolve(partNumber + ".json"), p);
        } catch (IOException e) {
            blobs.discard(staged);
            if (!uploads.containsKey(u.uploadId())) throw new S3Exception(S3Error.NO_SUCH_UPLOAD).with("UploadId", u.uploadId());
            throw new UncheckedIOException(e);
        }
        ConcurrentSkipListMap<Integer, PartMeta> map = parts.get(u.uploadId());
        if (map == null) throw new S3Exception(S3Error.NO_SUCH_UPLOAD).with("UploadId", u.uploadId());
        map.put(partNumber, p);
        return p;
    }

    public List<PartMeta> parts(MultipartUpload u) {
        ConcurrentSkipListMap<Integer, PartMeta> map = parts.get(u.uploadId());
        return map == null ? List.of() : List.copyOf(map.values());
    }

    public PartMeta part(MultipartUpload u, int partNumber) {
        ConcurrentSkipListMap<Integer, PartMeta> map = parts.get(u.uploadId());
        return map == null ? null : map.get(partNumber);
    }

    public List<MultipartUpload> uploads(String bucket) {
        requireBucket(bucket);
        return uploads.values().stream().filter(u -> u.bucket().equals(bucket))
                .sorted(Comparator.comparing(MultipartUpload::key, KeyOrder.INSTANCE)
                        .thenComparing(MultipartUpload::initiated))
                .toList();
    }

    /**
     * Completes an upload from the chosen parts (already validated by the caller): links the part files into the
     * blob store as the object's segments, and derives the multipart ETag and checksum.
     */
    public ObjectMeta completeUpload(MultipartUpload u, List<PartMeta> chosen, Precondition pre) {
        Path dir = multipartDir.resolve(u.uploadId());
        MessageDigest md5s = BlobStore.md5();
        ChecksumAlgorithm alg = ChecksumAlgorithm.fromApiName(u.checksumAlgorithm());
        boolean full = "FULL_OBJECT".equals(u.checksumType());
        byte[] crc = null;
        ChecksumAlgorithm.Hasher composite = full ? null : alg.newHasher();
        List<Segment> segments = new ArrayList<>();
        long size = 0;
        for (PartMeta p : chosen) {
            md5s.update(HEX.parseHex(p.md5()));
            byte[] partSum = Base64.getDecoder().decode(p.checksum());
            if (full) {
                crc = crc == null ? partSum : CrcCombine.of(alg).combine(crc, partSum, p.size());
            } else {
                composite.update(partSum, 0, partSum.length);
            }
            segments.add(new Segment(blobs.adoptCopy(dir.resolve(p.partNumber() + ".part")), p.size()));
            size += p.size();
        }
        int n = chosen.size();
        String checksum = full ? Base64.getEncoder().encodeToString(crc)
                : Base64.getEncoder().encodeToString(composite.digest()) + "-" + n;
        ObjectMeta meta = new ObjectMeta(u.key(), size, "\"" + HEX.formatHex(md5s.digest()) + "-" + n + "\"",
                clock.millis(), u.attributes().headers(), u.attributes().userMetadata(), u.checksumAlgorithm(),
                checksum, u.checksumType(), segments, n);
        ObjectMeta committed = commit(u.bucket(), meta, pre);
        removeUpload(u.uploadId());
        return committed;
    }

    public void abortUpload(String uploadId) {
        removeUpload(uploadId);
    }

    private void removeUpload(String uploadId) {
        uploads.remove(uploadId);
        parts.remove(uploadId);
        deleteTree(multipartDir.resolve(uploadId));
    }

    private void loadUploads() throws IOException {
        Files.createDirectories(multipartDir);
        try (Stream<Path> dirs = Files.list(multipartDir)) {
            for (Path dir : (Iterable<Path>) dirs::iterator) {
                Path meta = dir.resolve("upload.json");
                if (!Files.exists(meta)) {
                    deleteTree(dir);
                    continue;
                }
                MultipartUpload u = json.readValue(meta.toFile(), MultipartUpload.class);
                if (!buckets.containsKey(u.bucket())) {
                    deleteTree(dir);
                    continue;
                }
                ConcurrentSkipListMap<Integer, PartMeta> map = new ConcurrentSkipListMap<>();
                try (Stream<Path> files = Files.list(dir)) {
                    for (Path f : (Iterable<Path>) files::iterator) {
                        String name = f.getFileName().toString();
                        if (name.matches("\\d+\\.json") && Files.exists(dir.resolve(name.replace(".json", ".part")))) {
                            PartMeta p = json.readValue(f.toFile(), PartMeta.class);
                            map.put(p.partNumber(), p);
                        }
                    }
                }
                uploads.put(u.uploadId(), u);
                parts.put(u.uploadId(), map);
            }
        }
    }

    // ------------------------------------------------------------------ housekeeping

    private void maybeCompact() {
        if (journal.records() >= compactAfter) compact();
    }

    private void compact() {
        lock.lock();
        try {
            journal.compact(buckets);
            log.info("Compacted the metadata log into a snapshot ({} objects)", objectCount.get());
        } catch (IOException e) {
            log.error("Metadata compaction failed; the log keeps growing until the next attempt", e);
        } finally {
            lock.unlock();
        }
    }

    private void writeJsonAtomically(Path target, Object value) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        json.writeValue(tmp.toFile(), value);
        move(tmp, target);
    }

    private static void move(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void deleteTree(Path dir) {
        if (!Files.exists(dir)) return;
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : (Iterable<Path>) walk.sorted(Comparator.reverseOrder())::iterator) BlobStore.deleteQuietly(p);
        } catch (IOException e) {
            log.warn("Could not delete {}", dir, e);
        }
    }

    @Override
    public void close() throws IOException {
        lock.lock();
        try {
            journal.close();
        } finally {
            lock.unlock();
        }
    }
}
