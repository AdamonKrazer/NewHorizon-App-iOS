package com.newhorizon.thinclient.world;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;

/** Disk ring for compact vanilla section payloads evicted from active RAM. */
final class ColdChunkCache implements AutoCloseable {
    static final int KIND_SECTIONS = 0;
    static final int KIND_AUXILIARY = 1;
    static final int KIND_BIOMES = 2;
    static final int MAX_RECORD_BYTES = 256 * 1024;
    private static final int FILE_BYTES = 64 * 1024 * 1024;
    private static final int MAX_ENTRIES = 2048;

    private final int[] chunkXs = new int[MAX_ENTRIES];
    private final int[] chunkZs = new int[MAX_ENTRIES];
    private final byte[] kinds = new byte[MAX_ENTRIES];
    private final int[] offsets = new int[MAX_ENTRIES];
    private final int[] lengths = new int[MAX_ENTRIES];
    private final long[] stamps = new long[MAX_ENTRIES];
    private final Path path;
    private final RandomAccessFile file;
    private final FileChannel channel;
    private int count;
    private int writeOffset;
    private long clock;

    ColdChunkCache() throws IOException {
        path = Files.createTempFile("nh-thin-chunks-", ".cache");
        file = new RandomAccessFile(path.toFile(), "rw");
        file.setLength(FILE_BYTES);
        channel = file.getChannel();
    }

    boolean put(int chunkX, int chunkZ, ByteBuffer payload) throws IOException {
        return put(KIND_SECTIONS, chunkX, chunkZ, payload);
    }

    synchronized boolean put(int kind, int chunkX, int chunkZ, ByteBuffer payload) throws IOException {
        if (kind < KIND_SECTIONS || kind > KIND_BIOMES) return false;
        int length = payload.remaining();
        if (length <= 0 || length > MAX_RECORD_BYTES) return false;
        if (writeOffset + length > FILE_BYTES) writeOffset = 0;
        int offset = writeOffset;
        int end = offset + length;
        for (int index = count - 1; index >= 0; index--) {
            int storedEnd = offsets[index] + lengths[index];
            if (offset < storedEnd && end > offsets[index]) removeAt(index);
        }
        ByteBuffer source = payload.slice();
        long position = offset;
        while (source.hasRemaining()) {
            int written = channel.write(source, position);
            if (written <= 0) throw new IOException("cold chunk write stalled");
            position += written;
        }
        int slot = find(kind, chunkX, chunkZ);
        if (slot < 0) slot = count < MAX_ENTRIES ? count++ : oldestIndex();
        kinds[slot] = (byte) kind;
        chunkXs[slot] = chunkX;
        chunkZs[slot] = chunkZ;
        offsets[slot] = offset;
        lengths[slot] = length;
        stamps[slot] = ++clock;
        writeOffset = end;
        return true;
    }

    boolean readInto(int chunkX, int chunkZ, ByteBuffer destination) throws IOException {
        return readInto(KIND_SECTIONS, chunkX, chunkZ, destination);
    }

    synchronized boolean readInto(int kind, int chunkX, int chunkZ, ByteBuffer destination)
            throws IOException {
        int slot = find(kind, chunkX, chunkZ);
        if (slot < 0 || lengths[slot] > destination.capacity()) return false;
        destination.clear();
        destination.limit(lengths[slot]);
        long position = offsets[slot];
        while (destination.hasRemaining()) {
            int read = channel.read(destination, position);
            if (read <= 0) throw new IOException("cold chunk read stalled");
            position += read;
        }
        destination.flip();
        stamps[slot] = ++clock;
        return true;
    }

    synchronized void clear() {
        count = 0;
        writeOffset = 0;
    }

    synchronized int size() {
        return count;
    }

    synchronized void remove(int chunkX, int chunkZ) {
        for (int slot = count - 1; slot >= 0; slot--) {
            if (chunkXs[slot] == chunkX && chunkZs[slot] == chunkZ) removeAt(slot);
        }
    }

    private int find(int kind, int x, int z) {
        for (int index = 0; index < count; index++) {
            if (kinds[index] == kind && chunkXs[index] == x && chunkZs[index] == z) {
                return index;
            }
        }
        return -1;
    }

    synchronized boolean contains(int kind,int x,int z){return find(kind,x,z)>=0;}

    private int oldestIndex() {
        int oldest = 0;
        for (int index = 1; index < count; index++) {
            if (stamps[index] < stamps[oldest]) oldest = index;
        }
        return oldest;
    }

    private void removeAt(int index) {
        int last = --count;
        if (index == last) return;
        chunkXs[index] = chunkXs[last];
        chunkZs[index] = chunkZs[last];
        kinds[index] = kinds[last];
        offsets[index] = offsets[last];
        lengths[index] = lengths[last];
        stamps[index] = stamps[last];
    }

    @Override
    public synchronized void close() {
        try {
            channel.close();
        } catch (IOException ignored) {
        }
        try {
            file.close();
        } catch (IOException ignored) {
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
        }
    }
}
