package com.newhorizon.thinclient.memory;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Hard admission control for every sizeable client allocation.
 *
 * <p>The budget is deliberately not a memory-pressure callback. Reservations
 * are rejected before an allocation is made, so the runtime cannot grow first
 * and attempt to recover after Android has already started swapping.</p>
 */
public final class MemoryBudget {
    private final EnumMap<MemoryCategory, AtomicLong> used =
            new EnumMap<>(MemoryCategory.class);
    private final EnumMap<MemoryCategory, Long> limits =
            new EnumMap<>(MemoryCategory.class);

    public MemoryBudget(Map<MemoryCategory, Long> categoryLimits) {
        for (MemoryCategory category : MemoryCategory.values()) {
            Long limit = categoryLimits.get(category);
            if (limit == null || limit < 0L) {
                throw new IllegalArgumentException("Missing or negative limit for " + category);
            }
            limits.put(category, limit);
            used.put(category, new AtomicLong());
        }
    }

    public static MemoryBudget lowRamDefaults() {
        EnumMap<MemoryCategory, Long> limits = new EnumMap<>(MemoryCategory.class);
        // Includes the two bounded zlib scratch buffers required after the
        // server enables protocol compression.
        limits.put(MemoryCategory.NETWORK, mib(12));
        limits.put(MemoryCategory.WORLD, mib(72));
        limits.put(MemoryCategory.MESH, mib(96));
        limits.put(MemoryCategory.TEXTURE_STAGING, mib(32));
        limits.put(MemoryCategory.DISPLAY_STATE, mib(8));
        // Incremental Vorbis decoders and OpenAL queues, admitted before allocation.
        limits.put(MemoryCategory.AUDIO, mib(18));
        limits.put(MemoryCategory.TRANSIENT, mib(24));
        // File-backed mappings consume address space, but clean pages can be
        // discarded without first being copied into zRAM.
        limits.put(MemoryCategory.FILE_MAPPED, mib(768));
        return new MemoryBudget(limits);
    }

    public Lease tryReserve(MemoryCategory category, long bytes) {
        if (bytes < 0L) throw new IllegalArgumentException("bytes < 0");
        if (bytes == 0L) return new Lease(this, category, 0L);
        AtomicLong counter = used.get(category);
        long limit = limits.get(category);
        while (true) {
            long current = counter.get();
            if (bytes > limit - current) return null;
            if (counter.compareAndSet(current, current + bytes)) {
                return new Lease(this, category, bytes);
            }
        }
    }

    public Lease reserve(MemoryCategory category, long bytes) {
        Lease lease = tryReserve(category, bytes);
        if (lease == null) {
            throw new BudgetExceededException(category, bytes, used(category), limit(category));
        }
        return lease;
    }

    public long used(MemoryCategory category) {
        return used.get(category).get();
    }

    public long limit(MemoryCategory category) {
        return limits.get(category);
    }

    /** Replace a reservation without losing the old one when admission fails. */
    public Lease tryResize(Lease lease,long bytes) {
        if(lease.owner!=this || bytes<0)throw new IllegalArgumentException();
        synchronized(lease) {
            if(lease.closed.get())throw new IllegalStateException("Closed reservation");
            AtomicLong counter=used.get(lease.category);
            long delta=bytes-lease.bytes;
            while(true){long current=counter.get();
                if(delta>limits.get(lease.category)-current)return null;
                if(counter.compareAndSet(current,current+delta)){
                    lease.closed.set(true);return new Lease(this,lease.category,bytes);
                }
            }
        }
    }

    private void release(MemoryCategory category, long bytes) {
        long remaining = used.get(category).addAndGet(-bytes);
        if (remaining < 0L) {
            used.get(category).addAndGet(bytes);
            throw new IllegalStateException("Released more memory than reserved for " + category);
        }
    }

    private static long mib(long value) {
        return value * 1024L * 1024L;
    }

    public static final class Lease implements AutoCloseable {
        private final MemoryBudget owner;
        private final MemoryCategory category;
        private final long bytes;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Lease(MemoryBudget owner, MemoryCategory category, long bytes) {
            this.owner = owner;
            this.category = category;
            this.bytes = bytes;
        }

        public long bytes() {
            return bytes;
        }

        @Override
        public synchronized void close() {
            if (closed.compareAndSet(false, true)) owner.release(category, bytes);
        }
    }

    public static final class BudgetExceededException extends IllegalStateException {
        public final MemoryCategory category;
        public final long requested;
        public final long used;
        public final long limit;

        private BudgetExceededException(MemoryCategory category, long requested,
                                        long used, long limit) {
            super("Memory budget exceeded: category=" + category
                    + " requested=" + requested + " used=" + used + " limit=" + limit);
            this.category = category;
            this.requested = requested;
            this.used = used;
            this.limit = limit;
        }
    }
}
