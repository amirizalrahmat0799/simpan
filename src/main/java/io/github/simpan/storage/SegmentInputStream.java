package io.github.simpan.storage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.StandardOpenOption;
import java.util.List;

/** Reads a byte range that may span several segment files, opening each file only when it is reached. */
final class SegmentInputStream extends InputStream {

    private final BlobStore store;
    private final List<Segment> segments;
    private long remaining;
    private int index;
    private long skipInSegment;
    private InputStream current;

    SegmentInputStream(BlobStore store, List<Segment> segments, long offset, long length) {
        this.store = store;
        this.segments = segments;
        this.remaining = length;
        long pos = offset;
        int i = 0;
        while (i < segments.size() && pos >= segments.get(i).size()) {
            pos -= segments.get(i).size();
            i++;
        }
        this.index = i;
        this.skipInSegment = pos;
    }

    @Override
    public int read() throws IOException {
        byte[] b = new byte[1];
        int n = read(b, 0, 1);
        return n < 0 ? -1 : b[0] & 0xFF;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        while (remaining > 0) {
            if (current == null) {
                if (index >= segments.size()) return -1;
                SeekableByteChannel ch = FileChannel.open(store.path(segments.get(index).blob()), StandardOpenOption.READ);
                ch.position(skipInSegment);
                skipInSegment = 0;
                current = Channels.newInputStream(ch);
            }
            int n = current.read(b, off, (int) Math.min(len, remaining));
            if (n > 0) {
                remaining -= n;
                return n;
            }
            current.close();
            current = null;
            index++;
        }
        return -1;
    }

    @Override
    public void close() throws IOException {
        if (current != null) current.close();
    }
}
