package com.newhorizon.thinclient.protocol;

import com.newhorizon.thinclient.memory.MemoryBudget;
import com.newhorizon.thinclient.memory.MemoryCategory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Incremental Minecraft-style VarInt frame decoder with a fixed maximum size. */
public final class FramedPacketDecoder implements AutoCloseable {
    private final int maxFrameBytes;
    private final MemoryBudget.Lease lease;
    private final ByteBuffer staging;
    private boolean closed;

    public FramedPacketDecoder(MemoryBudget budget, int maxFrameBytes) {
        if (maxFrameBytes <= 0) throw new IllegalArgumentException();
        this.maxFrameBytes = maxFrameBytes;
        this.lease = budget.reserve(MemoryCategory.NETWORK, maxFrameBytes + 5L);
        try {
            this.staging = ByteBuffer.allocateDirect(maxFrameBytes + 5)
                    .order(ByteOrder.BIG_ENDIAN);
        } catch (RuntimeException | Error throwable) {
            lease.close();
            throw throwable;
        }
    }

    public void feed(ByteBuffer source, PacketConsumer consumer) throws ProtocolException {
        ensureOpen();
        while (source.hasRemaining()) {
            int copy = Math.min(source.remaining(), staging.remaining());
            if (copy > 0) {
                ByteBuffer part = source.slice();
                part.limit(copy);
                staging.put(part);
                source.position(source.position() + copy);
            }
            boolean progressed = drain(consumer);
            if (copy == 0 && !progressed) {
                throw new ProtocolException("Frame exceeds staging capacity");
            }
        }
        drain(consumer);
    }

    private boolean drain(PacketConsumer consumer) throws ProtocolException {
        staging.flip();
        boolean progressed = false;
        while (staging.hasRemaining()) {
            int start = staging.position();
            int length = VarInts.tryRead(staging);
            if (length < 0) {
                staging.position(start);
                break;
            }
            if (length > maxFrameBytes) {
                throw new ProtocolException("Frame too large: " + length);
            }
            if (staging.remaining() < length) {
                staging.position(start);
                break;
            }
            ByteBuffer packet = staging.slice().asReadOnlyBuffer().order(ByteOrder.BIG_ENDIAN);
            packet.limit(length);
            consumer.accept(packet);
            staging.position(staging.position() + length);
            progressed = true;
        }
        staging.compact();
        return progressed;
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        lease.close();
    }

    private void ensureOpen() {
        if (closed) throw new IllegalStateException("Decoder is closed");
    }

    public interface PacketConsumer {
        void accept(ByteBuffer packet) throws ProtocolException;
    }
}


