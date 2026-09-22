package com.newhorizon.thinclient.protocol;

import com.newhorizon.thinclient.memory.MemoryBudget;
import com.newhorizon.thinclient.memory.MemoryCategory;

import java.nio.ByteBuffer;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/** Bounded implementation of Minecraft's optional zlib packet compression. */
public final class MinecraftFrameCodec implements AutoCloseable {
    private final MemoryBudget budget;
    private final int maxPacketBytes;
    private int compressionThreshold = -1;
    private byte[] inputScratch;
    private byte[] outputScratch;
    private MemoryBudget.Lease compressionLease;

    public MinecraftFrameCodec(MemoryBudget budget, int maxPacketBytes) {
        if (maxPacketBytes <= 0) throw new IllegalArgumentException();
        this.budget = budget;
        this.maxPacketBytes = maxPacketBytes;
    }

    public void enableCompression(int threshold) {
        if (threshold < 0) throw new IllegalArgumentException("threshold");
        if (compressionThreshold >= 0) throw new IllegalStateException("Compression already enabled");
        long scratchBytes = maxPacketBytes * 2L + 1024L;
        compressionLease = budget.reserve(MemoryCategory.NETWORK, scratchBytes);
        try {
            inputScratch = new byte[maxPacketBytes];
            outputScratch = new byte[maxPacketBytes + 1024];
            compressionThreshold = threshold;
        } catch (RuntimeException | Error throwable) {
            compressionLease.close();
            compressionLease = null;
            throw throwable;
        }
    }

    public void encode(ByteBuffer output, ByteBuffer packet) throws ProtocolException {
        int packetBytes = packet.remaining();
        if (packetBytes > maxPacketBytes) throw new ProtocolException("Packet too large");
        if (compressionThreshold < 0) {
            VarInts.write(output, packetBytes);
            output.put(packet.slice());
            return;
        }
        if (packetBytes < compressionThreshold) {
            int frameBytes = 1 + packetBytes; // zero uncompressed-size VarInt
            VarInts.write(output, frameBytes);
            output.put((byte) 0);
            output.put(packet.slice());
            return;
        }

        ByteBuffer copy = packet.slice();
        copy.get(inputScratch, 0, packetBytes);
        Deflater deflater = new Deflater();
        int compressedBytes;
        try {
            deflater.setInput(inputScratch, 0, packetBytes);
            deflater.finish();
            compressedBytes = deflater.deflate(outputScratch);
            if (!deflater.finished()) throw new ProtocolException("Compressed packet exceeds limit");
        } finally {
            deflater.end();
        }
        int frameBytes = VarInts.encodedSize(packetBytes) + compressedBytes;
        VarInts.write(output, frameBytes);
        VarInts.write(output, packetBytes);
        output.put(outputScratch, 0, compressedBytes);
    }

    /** The returned buffer is valid only until the next decode call. */
    public ByteBuffer decode(ByteBuffer frame) throws ProtocolException {
        if (compressionThreshold < 0) return frame.slice().asReadOnlyBuffer();
        int uncompressedBytes = VarInts.read(frame);
        if (uncompressedBytes == 0) return frame.slice().asReadOnlyBuffer();
        if (uncompressedBytes < compressionThreshold || uncompressedBytes > maxPacketBytes) {
            throw new ProtocolException("Invalid uncompressed packet size " + uncompressedBytes);
        }
        int compressedBytes = frame.remaining();
        if (compressedBytes > inputScratch.length) throw new ProtocolException("Compressed data too large");
        frame.get(inputScratch, 0, compressedBytes);
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(inputScratch, 0, compressedBytes);
            int result = inflater.inflate(outputScratch, 0, uncompressedBytes);
            if (result != uncompressedBytes || !inflater.finished()) {
                throw new ProtocolException("Incomplete compressed packet");
            }
        } catch (DataFormatException exception) {
            throw new ProtocolException("Invalid zlib packet");
        } finally {
            inflater.end();
        }
        return ByteBuffer.wrap(outputScratch, 0, uncompressedBytes).asReadOnlyBuffer();
    }

    public int compressionThreshold() {
        return compressionThreshold;
    }

    @Override
    public void close() {
        inputScratch = null;
        outputScratch = null;
        // As with direct buffers, do not re-admit this category while the JVM
        // may still retain the old arrays. The runtime and budget die together.
        compressionLease = null;
    }
}


