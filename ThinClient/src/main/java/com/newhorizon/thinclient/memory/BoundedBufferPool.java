package com.newhorizon.thinclient.memory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.IdentityHashMap;

/** Fixed-capacity direct-buffer pool; it never allocates beyond maxBuffers. */
public final class BoundedBufferPool implements AutoCloseable {
    private final MemoryBudget budget;
    private final MemoryCategory category;
    private final int bufferBytes;
    private final int maxBuffers;
    private final ArrayDeque<ByteBuffer> available = new ArrayDeque<>();
    private final IdentityHashMap<ByteBuffer, Boolean> checkedOut = new IdentityHashMap<>();
    private final ArrayDeque<MemoryBudget.Lease> leases = new ArrayDeque<>();
    private int allocated;
    private boolean closed;

    public BoundedBufferPool(MemoryBudget budget, MemoryCategory category,
                             int bufferBytes, int maxBuffers) {
        if (bufferBytes <= 0 || maxBuffers <= 0) throw new IllegalArgumentException();
        this.budget = budget;
        this.category = category;
        this.bufferBytes = bufferBytes;
        this.maxBuffers = maxBuffers;
    }

    /** Returns null when all buffers are busy. The caller must apply backpressure. */
    public synchronized ByteBuffer acquire() {
        ensureOpen();
        ByteBuffer buffer = available.pollFirst();
        if (buffer == null) {
            if (allocated >= maxBuffers) return null;
            MemoryBudget.Lease lease = budget.tryReserve(category, bufferBytes);
            if (lease == null) return null;
            try {
                buffer = ByteBuffer.allocateDirect(bufferBytes).order(ByteOrder.BIG_ENDIAN);
            } catch (RuntimeException | Error throwable) {
                lease.close();
                throw throwable;
            }
            leases.addLast(lease);
            allocated++;
        }
        buffer.clear();
        checkedOut.put(buffer, Boolean.TRUE);
        return buffer;
    }

    public synchronized void release(ByteBuffer buffer) {
        ensureOpen();
        if (checkedOut.remove(buffer) == null) {
            throw new IllegalArgumentException("Buffer was not checked out from this pool");
        }
        buffer.clear();
        available.addLast(buffer);
    }

    public synchronized int allocatedCount() {
        return allocated;
    }

    public synchronized int checkedOutCount() {
        return checkedOut.size();
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        if (!checkedOut.isEmpty()) {
            throw new IllegalStateException("Cannot close pool with checked-out buffers");
        }
        available.clear();
        // DirectByteBuffer has no supported Java 8 explicit-free operation.
        // Keep the reservations until this process exits instead of pretending
        // the native pages were returned and admitting a second pool over them.
        leases.clear();
        allocated = 0;
    }

    private void ensureOpen() {
        if (closed) throw new IllegalStateException("Pool is closed");
    }
}


