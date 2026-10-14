package io.github.simpan.storage;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.CRC32;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The metadata log: every change to buckets and objects is appended as one JSON line, prefixed with its CRC-32,
 * and (with fsync on) flushed before the change becomes visible. At startup the newest snapshot is loaded and the
 * log replayed on top of it. A torn last line from a crash fails its CRC and is cut off.
 *
 * <p>When the log grows past a threshold it is compacted: the full state is written to a new snapshot, and a new,
 * empty log starts. The switch happens by atomic renames, so a crash during compaction loses nothing.
 */
final class Journal implements Closeable {

    private static final Logger log = LoggerFactory.getLogger(Journal.class);
    private static final Pattern SNAPSHOT = Pattern.compile("snapshot-(\\d+)\\.json");

    private final Path dir;
    private final boolean fsync;
    private final ObjectMapper json;
    private long generation;
    private FileChannel channel;
    private long records;

    Journal(Path root, boolean fsync, ObjectMapper json) {
        this.dir = root.resolve("meta");
        this.fsync = fsync;
        this.json = json;
    }

    /** Loads the snapshot and replays the log into {@code state}; returns how many log records were replayed. */
    long load(Map<String, BucketState> state) throws IOException {
        Files.createDirectories(dir);
        generation = 0;
        try (Stream<Path> files = Files.list(dir)) {
            for (Path p : (Iterable<Path>) files::iterator) {
                Matcher m = SNAPSHOT.matcher(p.getFileName().toString());
                if (m.matches()) generation = Math.max(generation, Long.parseLong(m.group(1)));
            }
        }
        if (generation > 0) readSnapshot(snapshot(generation), state);
        Path logFile = logFile(generation);
        long replayed = 0;
        long validBytes = 0;
        if (Files.exists(logFile)) {
            try (BufferedReader r = Files.newBufferedReader(logFile, StandardCharsets.UTF_8)) {
                String line;
                while ((line = r.readLine()) != null) {
                    int tab = line.indexOf('\t');
                    if (tab != 8) break;
                    String body = line.substring(tab + 1);
                    CRC32 crc = new CRC32();
                    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                    crc.update(bytes);
                    if (!String.format("%08x", crc.getValue()).equals(line.substring(0, 8))) break;
                    apply(json.readTree(body), state);
                    replayed++;
                    validBytes += line.getBytes(StandardCharsets.UTF_8).length + 1;
                }
            }
            long size = Files.size(logFile);
            if (validBytes < size) {
                log.warn("Metadata log {} has a torn or corrupt tail after {} records; truncating {} bytes",
                        logFile.getFileName(), replayed, size - validBytes);
                try (FileChannel ch = FileChannel.open(logFile, StandardOpenOption.WRITE)) {
                    ch.truncate(validBytes);
                    ch.force(true);
                }
            }
        }
        channel = FileChannel.open(logFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
        records = replayed;
        deleteOlderGenerations();
        return replayed;
    }

    long records() {
        return records;
    }

    void append(ObjectNode record) {
        try {
            byte[] body = json.writeValueAsBytes(record);
            CRC32 crc = new CRC32();
            crc.update(body);
            byte[] prefix = (String.format("%08x", crc.getValue()) + "\t").getBytes(StandardCharsets.US_ASCII);
            byte[] line = new byte[prefix.length + body.length + 1];
            System.arraycopy(prefix, 0, line, 0, prefix.length);
            System.arraycopy(body, 0, line, prefix.length, body.length);
            line[line.length - 1] = '\n';
            java.nio.ByteBuffer buf = java.nio.ByteBuffer.wrap(line);
            while (buf.hasRemaining()) channel.write(buf);
            if (fsync) channel.force(false);
            records++;
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write the metadata log", e);
        }
    }

    /** Writes the whole state as a new snapshot and starts a fresh log. Caller holds the write lock. */
    void compact(Map<String, BucketState> state) throws IOException {
        long next = generation + 1;
        Path tmp = dir.resolve("snapshot-" + next + ".json.tmp");
        try (OutputStream out = Files.newOutputStream(tmp);
             JsonGenerator g = json.getFactory().createGenerator(out)) {
            g.writeStartObject();
            g.writeNumberField("generation", next);
            g.writeArrayFieldStart("buckets");
            for (BucketState b : state.values()) {
                g.writeStartObject();
                g.writeStringField("name", b.info().name());
                g.writeNumberField("createdAt", b.info().createdAt());
                g.writeArrayFieldStart("objects");
                for (ObjectMeta m : b.objects().values()) json.writeValue(g, m);
                g.writeEndArray();
                g.writeEndObject();
            }
            g.writeEndArray();
            g.writeEndObject();
        }
        try (FileChannel ch = FileChannel.open(tmp, StandardOpenOption.WRITE)) {
            ch.force(true);
        }
        FileChannel nextLog = FileChannel.open(logFile(next), StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.APPEND);
        Files.move(tmp, snapshot(next), StandardCopyOption.ATOMIC_MOVE);
        BlobStore.forceDirectory(dir);
        channel.close();
        channel = nextLog;
        generation = next;
        records = 0;
        deleteOlderGenerations();
    }

    private void readSnapshot(Path file, Map<String, BucketState> state) throws IOException {
        try (JsonParser p = json.getFactory().createParser(file.toFile())) {
            expect(p.nextToken(), JsonToken.START_OBJECT);
            while (p.nextToken() == JsonToken.FIELD_NAME) {
                String field = p.currentName();
                p.nextToken();
                if (!field.equals("buckets")) {
                    p.skipChildren();
                    continue;
                }
                while (p.nextToken() == JsonToken.START_OBJECT) {
                    String name = null;
                    long createdAt = 0;
                    ConcurrentSkipListMap<String, ObjectMeta> objects = new ConcurrentSkipListMap<>(KeyOrder.INSTANCE);
                    while (p.nextToken() == JsonToken.FIELD_NAME) {
                        String f = p.currentName();
                        p.nextToken();
                        switch (f) {
                            case "name" -> name = p.getText();
                            case "createdAt" -> createdAt = p.getLongValue();
                            case "objects" -> {
                                while (p.nextToken() == JsonToken.START_OBJECT) {
                                    ObjectMeta m = json.readValue(p, ObjectMeta.class);
                                    objects.put(m.key(), m);
                                }
                            }
                            default -> p.skipChildren();
                        }
                    }
                    state.put(name, new BucketState(new BucketInfo(name, createdAt), objects));
                }
            }
        }
    }

    private void apply(JsonNode r, Map<String, BucketState> state) throws IOException {
        String bucket = r.path("b").asText();
        switch (r.path("op").asText()) {
            case "mkb" -> state.put(bucket, new BucketState(new BucketInfo(bucket, r.path("at").asLong()),
                    new ConcurrentSkipListMap<>(KeyOrder.INSTANCE)));
            case "rmb" -> state.remove(bucket);
            case "put" -> {
                ObjectMeta m = json.treeToValue(r.path("o"), ObjectMeta.class);
                BucketState b = state.get(bucket);
                if (b != null) b.objects().put(m.key(), m);
            }
            case "del" -> {
                BucketState b = state.get(bucket);
                if (b != null) b.objects().remove(r.path("k").asText());
            }
            default -> throw new IOException("Unknown metadata record " + r);
        }
    }

    private void deleteOlderGenerations() throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            for (Path p : (Iterable<Path>) files::iterator) {
                String name = p.getFileName().toString();
                Matcher m = Pattern.compile("(?:snapshot|journal)-(\\d+)\\.(?:json|log)").matcher(name);
                if (m.matches() && Long.parseLong(m.group(1)) < generation) Files.deleteIfExists(p);
            }
        }
    }

    private Path snapshot(long g) {
        return dir.resolve("snapshot-" + g + ".json");
    }

    private Path logFile(long g) {
        return dir.resolve("journal-" + g + ".log");
    }

    private static void expect(JsonToken actual, JsonToken expected) throws IOException {
        if (actual != expected) throw new IOException("Corrupt snapshot: expected " + expected + " but found " + actual);
    }

    @Override
    public void close() throws IOException {
        if (channel != null) channel.close();
    }
}
