package com.newhorizon.thinclient.memory;

import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;

/** Read-only, file-backed asset pack. No decoded object graph is retained. */
public final class MappedAssetStore implements Closeable {
    private final FileInputStream stream;
    private final FileChannel channel;
    private final MappedByteBuffer mapping;
    private final MemoryBudget.Lease lease;

    public MappedAssetStore(File file, MemoryBudget budget) throws IOException {
        stream = new FileInputStream(file);
        channel = stream.getChannel();
        long size = channel.size();
        lease = budget.reserve(MemoryCategory.FILE_MAPPED, size);
        try {
            mapping = channel.map(FileChannel.MapMode.READ_ONLY, 0L, size);
        } catch (IOException throwable) {
            lease.close();
            try {
                channel.close();
            } finally {
                stream.close();
            }
            throw throwable;
        } catch (RuntimeException | Error throwable) {
            lease.close();
            try {
                channel.close();
            } finally {
                stream.close();
            }
            throw throwable;
        }
    }

    public long size() {
        return mapping.capacity();
    }

    public ByteBuffer region(int offset, int length) {
        if (offset < 0 || length < 0 || offset > mapping.capacity() - length) {
            throw new IndexOutOfBoundsException();
        }
        ByteBuffer duplicate = mapping.asReadOnlyBuffer().order(ByteOrder.BIG_ENDIAN);
        duplicate.position(offset);
        duplicate.limit(offset + length);
        return duplicate.slice().asReadOnlyBuffer().order(ByteOrder.BIG_ENDIAN);
    }

    @Override
    public void close() throws IOException {
        // Java 8 has no supported explicit unmap API. Closing the channel makes
        // the store unreachable; the mapping remains clean/file-backed until GC.
        try {
            channel.close();
        } finally {
            try {
                stream.close();
            } finally {
                // The supported Java 8 API cannot explicitly unmap. Retain the
                // virtual/file-mapping reservation until process exit so a
                // reopen loop cannot silently exceed the mapped budget.
            }
        }
    }
}


