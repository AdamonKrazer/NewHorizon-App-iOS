package com.newhorizon.thinclient.world;

import com.newhorizon.thinclient.protocol.BinaryCodec;
import com.newhorizon.thinclient.protocol.ProtocolException;
import com.newhorizon.thinclient.protocol.VarInts;

import java.nio.ByteBuffer;

/** Allocation-free decoder for the block-state half of vanilla 1.20.1 chunks. */
final class VanillaChunkDecoder {
    private static final int BLOCKS_PER_SECTION = 4096;
    private static final int MAX_PALETTE = 256;
    private final int[] palette = new int[MAX_PALETTE];

    long decodeSections(ByteBuffer input, SurfaceChunk target, int worldHeight)
            throws ProtocolException {
        if (worldHeight <= 0 || (worldHeight & 15) != 0) {
            throw new ProtocolException("Invalid vanilla world height " + worldHeight);
        }
        int sectionCount = worldHeight >>> 4;
        int expectedNonEmptyTotal = 0;
        int decodedNonEmptyTotal = 0;
        for (int section = 0; section < sectionCount; section++) {
            BinaryCodec.require(input, Short.BYTES);
            int expectedNonEmpty = input.getShort() & 0xffff;
            int decodedNonEmpty = decodeBlockContainer(input, target, section << 4);
            expectedNonEmptyTotal += expectedNonEmpty;
            decodedNonEmptyTotal += decodedNonEmpty;
            decodeBiomeContainer(input, target, section << 2);
        }
        // Mohist/Forge may append a small extension after the vanilla section
        // stream. It belongs to the same bounded packet and is discarded here.
        return (long) expectedNonEmptyTotal << 32
                | decodedNonEmptyTotal & 0xffff_ffffL;
    }

    void decodeBiomeUpdate(ByteBuffer input, SurfaceChunk target, int worldHeight)
            throws ProtocolException {
        if (worldHeight <= 0 || (worldHeight & 15) != 0) {
            throw new ProtocolException("Invalid vanilla world height " + worldHeight);
        }
        int sectionCount = worldHeight >>> 4;
        for (int section = 0; section < sectionCount; section++) {
            decodeBiomeContainer(input, target, section << 2);
        }
        if (input.hasRemaining()) {
            throw new ProtocolException("Trailing chunk biome bytes " + input.remaining());
        }
    }

    private int decodeBlockContainer(ByteBuffer input, SurfaceChunk target,
                                     int relativeSectionY)
            throws ProtocolException {
        BinaryCodec.require(input, 1);
        int bits = input.get() & 0xff;
        int paletteSize;
        if (bits == 0) {
            palette[0] = readStateId(input);
            paletteSize = 1;
        } else if (bits <= 8) {
            paletteSize = VarInts.read(input);
            if (paletteSize <= 0 || paletteSize > MAX_PALETTE) {
                throw new ProtocolException("Block palette outside limit " + paletteSize);
            }
            for (int index = 0; index < paletteSize; index++) {
                palette[index] = readStateId(input);
            }
        } else {
            paletteSize = 0; // global block-state palette
        }

        int words = VarInts.read(input);
        if (words < 0 || words > BLOCKS_PER_SECTION) {
            throw new ProtocolException("Block storage outside limit " + words);
        }
        BinaryCodec.require(input, words * Long.BYTES);
        int wordOffset = input.position();
        input.position(wordOffset + words * Long.BYTES);

        if (bits == 0) {
            if (words != 0) throw new ProtocolException("Single block palette has storage");
            fillSection(target, relativeSectionY, palette[0]);
            return nonEmptyContribution(palette[0]) * BLOCKS_PER_SECTION;
        }
        if (bits > 31) throw new ProtocolException("Invalid block bits " + bits);
        int valuesPerWord = Long.SIZE / bits;
        int expectedWords = (BLOCKS_PER_SECTION + valuesPerWord - 1) / valuesPerWord;
        if (words != expectedWords) {
            throw new ProtocolException("Unexpected block storage words " + words
                    + " expected=" + expectedWords);
        }
        long mask = (1L << bits) - 1L;
        int decodedNonEmpty = 0;
        for (int packedIndex = 0; packedIndex < BLOCKS_PER_SECTION; packedIndex++) {
            int wordIndex = packedIndex / valuesPerWord;
            int bitOffset = (packedIndex % valuesPerWord) * bits;
            int value = (int) ((input.getLong(wordOffset + wordIndex * Long.BYTES)
                    >>> bitOffset) & mask);
            int stateId;
            if (paletteSize == 0) {
                stateId = value;
            } else {
                if (value >= paletteSize) {
                    throw new ProtocolException("Block palette index outside limit " + value);
                }
                stateId = palette[value];
            }
            putPackedIndex(target, relativeSectionY, packedIndex, stateId);
            decodedNonEmpty += nonEmptyContribution(stateId);
        }
        return decodedNonEmpty;
    }

    /**
     * Vanilla's section counter increments once for a non-air block state and
     * once more for a non-empty fluid state (including waterlogged blocks).
     */
    private static int nonEmptyContribution(int stateId) {
        int count = BlockStatePhysics.isAir(stateId) ? 0 : 1;
        int flags = BlockStatePhysics.flags(stateId);
        if ((flags & (BlockStatePhysics.WATER | BlockStatePhysics.LAVA)) != 0) count++;
        return count;
    }

    private void decodeBiomeContainer(ByteBuffer input, SurfaceChunk target,
                                      int relativeQuartY) throws ProtocolException {
        BinaryCodec.require(input, 1);
        int bits = input.get() & 0xff;
        int paletteSize;
        if (bits == 0) {
            palette[0] = readStateId(input);
            paletteSize = 1;
        } else if (bits <= 3) {
            paletteSize = VarInts.read(input);
            if (paletteSize <= 0 || paletteSize > 64) {
                throw new ProtocolException("Biome palette outside limit " + paletteSize);
            }
            for (int index = 0; index < paletteSize; index++) {
                palette[index] = readStateId(input);
            }
        } else {
            if (bits > 31) throw new ProtocolException("Invalid biome bits " + bits);
            paletteSize = 0;
        }
        int words = VarInts.read(input);
        if (words < 0 || words > 64) {
            throw new ProtocolException("Biome storage outside limit " + words);
        }
        BinaryCodec.require(input, words * Long.BYTES);
        int wordOffset = input.position();
        input.position(wordOffset + words * Long.BYTES);
        if (bits == 0) {
            if (words != 0) throw new ProtocolException("Single biome palette has storage");
            for (int index = 0; index < 64; index++) {
                putBiomeIndex(target, relativeQuartY, index, palette[0]);
            }
            return;
        }
        int valuesPerWord = Long.SIZE / bits;
        int expectedWords = (64 + valuesPerWord - 1) / valuesPerWord;
        if (words != expectedWords) {
            throw new ProtocolException("Unexpected biome storage words " + words
                    + " expected=" + expectedWords);
        }
        long mask = (1L << bits) - 1L;
        for (int index = 0; index < 64; index++) {
            int bitOffset = (index % valuesPerWord) * bits;
            int value = (int) ((input.getLong(wordOffset
                    + index / valuesPerWord * Long.BYTES) >>> bitOffset) & mask);
            int biomeId;
            if (paletteSize == 0) {
                biomeId = value;
            } else {
                if (value >= paletteSize) {
                    throw new ProtocolException("Biome palette index outside limit " + value);
                }
                biomeId = palette[value];
            }
            putBiomeIndex(target, relativeQuartY, index, biomeId);
        }
    }

    private static void putBiomeIndex(SurfaceChunk target, int relativeQuartY,
                                      int index, int biomeId) {
        int x = index & 3;
        int z = (index >>> 2) & 3;
        int y = relativeQuartY + ((index >>> 4) & 3);
        target.putBiomeState(x, y, z, biomeId);
    }

    private static int readStateId(ByteBuffer input) throws ProtocolException {
        int stateId = VarInts.read(input);
        if (stateId < 0) throw new ProtocolException("Negative block state id");
        return stateId;
    }

    private static void fillSection(SurfaceChunk target, int relativeY, int stateId) {
        for (int index = 0; index < BLOCKS_PER_SECTION; index++) {
            putPackedIndex(target, relativeY, index, stateId);
        }
    }

    private static void putPackedIndex(SurfaceChunk target, int relativeY,
                                       int index, int stateId) {
        int x = index & 15;
        int z = (index >>> 4) & 15;
        int y = relativeY + ((index >>> 8) & 15);
        target.putFullBlockState(x, y, z, stateId);
    }
}
