package com.newhorizon.thinclient.world;

import com.newhorizon.thinclient.protocol.BinaryCodec;
import com.newhorizon.thinclient.protocol.NbtSkipper;
import com.newhorizon.thinclient.protocol.ProtocolException;
import com.newhorizon.thinclient.protocol.VarInts;

import java.nio.ByteBuffer;

/** Decodes block entities and light from protocol-763 world packets. */
final class VanillaChunkAuxDecoder {
    private static final int MAX_BLOCK_ENTITIES = 4096;
    private static final int MAX_LIGHT_ARRAYS = 64;

    void decodeInitial(ByteBuffer input, SurfaceChunk target, int worldHeight)
            throws ProtocolException {
        int count = VarInts.read(input);
        if (count < 0 || count > MAX_BLOCK_ENTITIES) {
            throw new ProtocolException("Invalid block entity count " + count);
        }
        target.beginBlockEntityUpdate();
        for (int index = 0; index < count; index++) {
            BinaryCodec.require(input, 3);
            int packedXZ = input.get() & 0xff;
            int worldY = input.getShort();
            int typeId = VarInts.read(input);
            target.putBlockEntity(packedXZ >>> 4, worldY, packedXZ & 15, typeId);
            NbtSkipper.skipRoot(input);
        }
        decodeLight(input, target, worldHeight, true);
        if (input.hasRemaining()) {
            throw new ProtocolException("Trailing chunk auxiliary bytes " + input.remaining());
        }
    }

    void decodeLightUpdate(ByteBuffer input, SurfaceChunk target, int worldHeight)
            throws ProtocolException {
        decodeLight(input, target, worldHeight, false);
        if (input.hasRemaining()) {
            throw new ProtocolException("Trailing chunk light bytes " + input.remaining());
        }
    }

    private void decodeLight(ByteBuffer input, SurfaceChunk target,
                             int worldHeight, boolean initial)
            throws ProtocolException {
        int lightSections = (worldHeight >>> 4) + 2;
        if (lightSections <= 0 || lightSections > SurfaceChunk.MAX_LIGHT_SECTIONS) {
            throw new ProtocolException("Invalid light section count " + lightSections);
        }
        long skyMask = readMask(input);
        long blockMask = readMask(input);
        long emptySkyMask = readMask(input);
        long emptyBlockMask = readMask(input);
        if (initial) target.clearLightData();
        clearMaskedSections(target, true, emptySkyMask, lightSections);
        clearMaskedSections(target, false, emptyBlockMask, lightSections);
        readLightArrays(input, target, true, skyMask, lightSections);
        readLightArrays(input, target, false, blockMask, lightSections);
    }

    private static long readMask(ByteBuffer input) throws ProtocolException {
        int words = VarInts.read(input);
        if (words < 0 || words > 1) {
            throw new ProtocolException("Light mask outside supported height " + words);
        }
        BinaryCodec.require(input, words * Long.BYTES);
        return words == 0 ? 0L : input.getLong();
    }

    private static void clearMaskedSections(SurfaceChunk target, boolean sky,
                                            long mask, int sectionCount) {
        for (int section = 0; section < sectionCount; section++) {
            if ((mask & (1L << section)) != 0L) {
                target.clearLightSection(sky, section);
            }
        }
    }

    private static void readLightArrays(ByteBuffer input, SurfaceChunk target,
                                        boolean sky, long mask, int sectionCount)
            throws ProtocolException {
        int count = VarInts.read(input);
        if (count < 0 || count > MAX_LIGHT_ARRAYS || count != Long.bitCount(mask)) {
            throw new ProtocolException("Light array count does not match mask " + count);
        }
        int array = 0;
        for (int section = 0; section < Long.SIZE && array < count; section++) {
            if ((mask & (1L << section)) == 0L) continue;
            int length = VarInts.read(input);
            if (length != SurfaceChunk.LIGHT_SECTION_BYTES) {
                throw new ProtocolException("Invalid light array length " + length);
            }
            BinaryCodec.require(input, length);
            ByteBuffer bytes = input.slice();
            bytes.limit(length);
            if (section < sectionCount) target.putLightSection(sky, section, bytes);
            input.position(input.position() + length);
            array++;
        }
    }
}
