package io.github.simpan.storage;

import io.github.simpan.api.S3Error;
import io.github.simpan.api.S3Exception;
import io.github.simpan.io.ChecksumAlgorithm.Hasher;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Immutable blob files. A blob is written to {@code tmp/}, flushed to disk, then renamed into
 * {@code data/xx/<id>} in one atomic step, so a crash never leaves a half-written blob that something refers to.
 */
public final class BlobStore {

    private static final char[] ALPHABET = "abcdefghijklmnopqrstuvwxyz234567".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int BUFFER = 64 * 1024;

    private final Path data;
    private final Path tmp;
    private final boolean fsync;

    public BlobStore(Path root, boolean fsync) {
        this.data = root.resolve("data");
        this.tmp = root.resolve("tmp");
        this.fsync = fsync;
        try {
            Files.createDirectories(data);
            Files.createDirectories(tmp);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static String newId() {
        char[] c = new char[26];
        for (int i = 0; i < c.length; i++) c[i] = ALPHABET[RANDOM.nextInt(32)];
        return new String(c);
    }

    public Path path(String blob) {
        return data.resolve(blob.substring(0, 2)).resolve(blob);
    }

    /**
     * Streams {@code in} to a temporary file, updating MD5 and the extra hashers as bytes arrive. Fails with
     * EntityTooLarge past {@code maxBytes}.
     */
    public StagedBlob stage(InputStream in, long maxBytes, List<Hasher> hashers) {
        Path file = tmp.resolve(newId());
        MessageDigest md5 = md5();
        long size = 0;
        try (OutputStream out = Files.newOutputStream(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            byte[] buf = new byte[BUFFER];
            int n;
            while ((n = in.read(buf)) > 0) {
                size += n;
                if (size > maxBytes) throw new S3Exception(S3Error.ENTITY_TOO_LARGE).with("MaxSizeAllowed", String.valueOf(maxBytes));
                md5.update(buf, 0, n);
                for (Hasher h : hashers) h.update(buf, 0, n);
                out.write(buf, 0, n);
            }
        } catch (IOException | RuntimeException e) {
            deleteQuietly(file);
            if (e instanceof S3Exception s) throw s;
            if (e instanceof IOException io) throw new S3Exception(S3Error.INCOMPLETE_BODY, "Upload interrupted: " + io.getMessage(), io);
            throw (RuntimeException) e;
        }
        return new StagedBlob(file, size, md5.digest());
    }

    /** Moves a staged file into the store and returns its blob id. */
    public String adopt(StagedBlob staged) {
        return adopt(staged.file());
    }

    /** Hard-links (or copies) an existing file into the store, leaving the original in place. */
    public String adoptCopy(Path source) {
        String id = newId();
        Path target = path(id);
        try {
            Files.createDirectories(target.getParent());
            try {
                Files.createLink(target, source);
            } catch (UnsupportedOperationException | IOException e) {
                Files.copy(source, target);
                force(target);
            }
            return id;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private String adopt(Path file) {
        try {
            force(file);
            String id = newId();
            Path target = path(id);
            Files.createDirectories(target.getParent());
            try {
                Files.move(file, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(file, target);
            }
            if (fsync) forceDirectory(target.getParent());
            return id;
        } catch (FileAlreadyExistsException e) {
            return adopt(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Copies bytes [offset, offset + length) of the given segments into a new staged file. */
    public StagedBlob stageCopy(List<Segment> segments, long offset, long length, List<Hasher> hashers) {
        try (InputStream in = open(segments, offset, length)) {
            return stage(in, Long.MAX_VALUE, hashers);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Reads [offset, offset + length) across an object's segments. */
    public InputStream open(List<Segment> segments, long offset, long length) throws IOException {
        return new SegmentInputStream(this, segments, offset, length);
    }

    public void delete(String blob) {
        deleteQuietly(path(blob));
    }

    public void discard(StagedBlob staged) {
        if (staged != null) deleteQuietly(staged.file());
    }

    /** Deletes temporary files and blobs that no object references (left behind by a crash). */
    public int collectGarbage(Set<String> referenced) {
        int removed = 0;
        try (Stream<Path> files = Files.list(tmp)) {
            for (Path p : (Iterable<Path>) files::iterator) {
                deleteQuietly(p);
                removed++;
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Set<String> seen = new HashSet<>();
        try (Stream<Path> dirs = Files.list(data)) {
            for (Path dir : (Iterable<Path>) dirs::iterator) {
                if (!Files.isDirectory(dir)) continue;
                try (Stream<Path> blobs = Files.list(dir)) {
                    for (Path b : (Iterable<Path>) blobs::iterator) {
                        String id = b.getFileName().toString();
                        seen.add(id);
                        if (!referenced.contains(id)) {
                            deleteQuietly(b);
                            removed++;
                        }
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return removed;
    }

    private void force(Path file) throws IOException {
        if (!fsync) return;
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.WRITE)) {
            ch.force(true);
        }
    }

    static void forceDirectory(Path dir) {
        try (FileChannel ch = FileChannel.open(dir, StandardOpenOption.READ)) {
            ch.force(true);
        } catch (IOException ignored) {
            // not supported on every OS (Windows); the rename itself is still atomic
        }
    }

    static void deleteQuietly(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (NoSuchFileException ignored) {
            // already gone
        } catch (IOException ignored) {
            // best effort; garbage collection retries at the next start
        }
    }

    static MessageDigest md5() {
        try {
            return MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
