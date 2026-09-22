package com.newhorizon.thinclient.world;

import java.nio.ByteBuffer;

/** Reusable fixed-size slot containing only exposed world cells. */
public final class SurfaceChunk {
    public static final int RECORD_BYTES = 8;
    public static final int COLLIDABLE_FLAG = 1 << 23;
    static final int MAX_FULL_BLOCKS = 16 * 16 * 512;
    public static final int FULL_BLOCK_BYTES = MAX_FULL_BLOCKS * Short.BYTES;
    static final int MAX_BIOMES = (16 / 4) * (16 / 4) * (512 / 4);
    public static final int BIOME_BYTES = MAX_BIOMES * Short.BYTES;
    static final int MAX_LIGHT_SECTIONS = 512 / 16 + 2;
    public static final int LIGHT_SECTION_BYTES = 2048;
    public static final int LIGHT_BYTES = MAX_LIGHT_SECTIONS * LIGHT_SECTION_BYTES * 2;
    private static final int MAX_BLOCK_ENTITIES = 256;
    public static final int BLOCK_ENTITY_BYTES = MAX_BLOCK_ENTITIES * 8;
    private static final int COLLISION_WORDS = (16 * 16 * 512) / Long.SIZE;
    public int chunkX;
    public int chunkZ;
    public int revision;
    public int recordCount;
    long lastTouched;
    final ByteBuffer records;
    final ByteBuffer fullBlockStates;
    final ByteBuffer biomeStates;
    final ByteBuffer lightData;
    final ByteBuffer blockEntities;
    boolean hasFullBlockStates;
    int fullBlockHeight;
    private long skyLightPresent;
    private long blockLightPresent;
    private int blockEntityCount;
    private final long[] collisionBits = new long[COLLISION_WORDS];

    SurfaceChunk(ByteBuffer records, ByteBuffer fullBlockStates,
                 ByteBuffer biomeStates, ByteBuffer lightData,
                 ByteBuffer blockEntities) {
        this.records = records;
        this.fullBlockStates = fullBlockStates;
        this.biomeStates = biomeStates;
        this.lightData = lightData;
        this.blockEntities = blockEntities;
    }

    void reset(int x, int z) {
        chunkX = x;
        chunkZ = z;
        revision = 0;
        recordCount = 0;
        hasFullBlockStates = false;
        fullBlockHeight = 0;
        skyLightPresent = 0L;
        blockLightPresent = 0L;
        blockEntityCount = 0;
        java.util.Arrays.fill(collisionBits, 0L);
    }

    public ByteBuffer records() {
        ByteBuffer view = records.asReadOnlyBuffer();
        view.position(0);
        view.limit(recordCount * RECORD_BYTES);
        return view;
    }

    /** Worker-owned copy; caller holds the source world's lock only for bulk copies. */
    void copyFrom(SurfaceChunk source) {
        chunkX=source.chunkX; chunkZ=source.chunkZ; revision=source.revision;
        recordCount=source.recordCount; hasFullBlockStates=source.hasFullBlockStates;
        fullBlockHeight=source.fullBlockHeight; skyLightPresent=source.skyLightPresent;
        blockLightPresent=source.blockLightPresent; blockEntityCount=source.blockEntityCount;
        copy(source.records,records,recordCount*RECORD_BYTES);
        copy(source.fullBlockStates,fullBlockStates,fullBlockHeight*256*2);
        copy(source.biomeStates,biomeStates,BIOME_BYTES);
        copy(source.lightData,lightData,LIGHT_BYTES);
        if(blockEntities.capacity()>=blockEntityCount*8)copy(source.blockEntities,blockEntities,blockEntityCount*8);
        else blockEntityCount=0;
    }

    private static void copy(ByteBuffer from,ByteBuffer to,int length) {
        ByteBuffer view=from.duplicate(); view.clear(); view.limit(length);
        to.clear(); to.put(view); to.clear();
    }

    void rebuildCollisionIndex() {
        java.util.Arrays.fill(collisionBits, 0L);
        for (int offset = 0; offset < recordCount * RECORD_BYTES; offset += RECORD_BYTES) {
            int packed = records.getInt(offset);
            if ((packed & COLLIDABLE_FLAG) == 0) continue;
            int bit = relativeY(packed) * 256 + localZ(packed) * 16 + localX(packed);
            collisionBits[bit >>> 6] |= 1L << (bit & 63);
        }
    }

    boolean isCollidable(int x, int relativeY, int z) {
        if ((x & ~15) != 0 || (z & ~15) != 0 || (relativeY & ~511) != 0) {
            return false;
        }
        int bit = relativeY * 256 + z * 16 + x;
        return (collisionBits[bit >>> 6] & (1L << (bit & 63))) != 0L;
    }

    boolean containsCell(int x, int relativeY, int z) {
        if ((x & ~15) != 0 || (z & ~15) != 0 || (relativeY & ~511) != 0) {
            return false;
        }
        for (int offset = 0; offset < recordCount * RECORD_BYTES;
             offset += RECORD_BYTES) {
            int packed = records.getInt(offset);
            if (localX(packed) == x && localZ(packed) == z
                    && relativeY(packed) == relativeY) return true;
        }
        return false;
    }

    boolean isTargetableCell(int x, int relativeY, int z) {
        if ((x & ~15) != 0 || (z & ~15) != 0 || (relativeY & ~511) != 0) {
            return false;
        }
        for (int offset = 0; offset < recordCount * RECORD_BYTES;
             offset += RECORD_BYTES) {
            int packed = records.getInt(offset);
            if (localX(packed) != x || localZ(packed) != z
                    || relativeY(packed) != relativeY) continue;
            int color = records.getInt(offset + Integer.BYTES);
            return color != 0x3f76e4ff && color != 0xff6b16ff;
        }
        return false;
    }

    boolean removeCell(int x, int relativeY, int z) {
        if ((x & ~15) != 0 || (z & ~15) != 0 || (relativeY & ~511) != 0) {
            return false;
        }
        int end = recordCount * RECORD_BYTES;
        for (int offset = 0; offset < end; offset += RECORD_BYTES) {
            int packed = records.getInt(offset);
            if (localX(packed) != x || localZ(packed) != z
                    || relativeY(packed) != relativeY) continue;
            for (int source = offset + RECORD_BYTES; source < end;
                 source += RECORD_BYTES) {
                records.putLong(source - RECORD_BYTES, records.getLong(source));
            }
            recordCount--;
            rebuildCollisionIndex();
            revision++;
            return true;
        }
        return false;
    }

    void beginFullBlockUpdate() {
        hasFullBlockStates = false;
    }

    void putFullBlockState(int x, int relativeY, int z, int stateId) {
        if ((x & ~15) != 0 || (z & ~15) != 0 || (relativeY & ~511) != 0) {
            throw new IllegalArgumentException("full block position");
        }
        int stored = stateId >= 0 && stateId < 0xffff ? stateId : 1;
        fullBlockStates.putShort(fullBlockIndex(x, relativeY, z) * Short.BYTES,
                (short) stored);
    }

    void finishFullBlockUpdate(int height) {
        hasFullBlockStates = true;
        fullBlockHeight = Math.max(0, Math.min(512, height));
    }

    public boolean hasFullBlockStates() {
        return hasFullBlockStates;
    }

    public int fullBlockHeight() {
        return fullBlockHeight;
    }

    public int fullBlockState(int x, int relativeY, int z) {
        if (!hasFullBlockStates || (x & ~15) != 0 || (z & ~15) != 0
                || (relativeY & ~511) != 0) return -1;
        return fullBlockStates.getShort(
                fullBlockIndex(x, relativeY, z) * Short.BYTES) & 0xffff;
    }

    void putBiomeState(int x, int relativeY, int z, int biomeId) {
        if ((x & ~3) != 0 || (z & ~3) != 0 || relativeY < 0 || relativeY >= 128) {
            throw new IllegalArgumentException("biome position");
        }
        int stored = biomeId >= 0 && biomeId < 0xffff ? biomeId : 0;
        int index = relativeY * 16 + z * 4 + x;
        biomeStates.putShort(index * Short.BYTES, (short) stored);
    }

    public int biomeState(int quartX, int quartY, int quartZ) {
        if ((quartX & ~3) != 0 || (quartZ & ~3) != 0
                || quartY < 0 || quartY >= 128) return -1;
        int index = quartY * 16 + quartZ * 4 + quartX;
        if(index*Short.BYTES+2>biomeStates.limit())return -1;
        return biomeStates.getShort(index * Short.BYTES) & 0xffff;
    }

    void clearLightData() {
        for (int offset = 0; offset < lightData.capacity(); offset += Long.BYTES) {
            lightData.putLong(offset, 0L);
        }
        skyLightPresent = 0L;
        blockLightPresent = 0L;
    }

    void putLightSection(boolean sky, int sectionIndex, ByteBuffer source) {
        if (sectionIndex < 0 || sectionIndex >= MAX_LIGHT_SECTIONS
                || source.remaining() != LIGHT_SECTION_BYTES) {
            throw new IllegalArgumentException("light section");
        }
        int layerBase = sky ? 0 : MAX_LIGHT_SECTIONS * LIGHT_SECTION_BYTES;
        int target = layerBase + sectionIndex * LIGHT_SECTION_BYTES;
        ByteBuffer bytes = source.slice();
        for (int index = 0; index < LIGHT_SECTION_BYTES; index++) {
            lightData.put(target + index, bytes.get(index));
        }
        long bit = 1L << sectionIndex;
        if (sky) skyLightPresent |= bit;
        else blockLightPresent |= bit;
    }

    void clearLightSection(boolean sky, int sectionIndex) {
        if (sectionIndex < 0 || sectionIndex >= MAX_LIGHT_SECTIONS) return;
        int layerBase = sky ? 0 : MAX_LIGHT_SECTIONS * LIGHT_SECTION_BYTES;
        int target = layerBase + sectionIndex * LIGHT_SECTION_BYTES;
        for (int index = 0; index < LIGHT_SECTION_BYTES; index += Long.BYTES) {
            lightData.putLong(target + index, 0L);
        }
        long bit = 1L << sectionIndex;
        if (sky) skyLightPresent |= bit;
        else blockLightPresent |= bit;
    }

    public int combinedLight(int x, int relativeY, int z) {
        if ((x & ~15) != 0 || (z & ~15) != 0
                || relativeY < 0 || relativeY >= fullBlockHeight) return 15;
        int section = (relativeY >>> 4) + 1;
        int packedIndex = ((relativeY & 15) << 8) | (z << 4) | x;
        int sky = lightNibble(true, section, packedIndex, 15);
        int block = lightNibble(false, section, packedIndex, 0);
        return Math.max(sky, block);
    }

    public int light(boolean sky, int x, int relativeY, int z) {
        if (relativeY < 0 || relativeY >= fullBlockHeight) return sky ? 15 : 0;
        return lightNibble(sky, (relativeY >>> 4) + 1,
                ((relativeY & 15) << 8) | ((z & 15) << 4) | (x & 15), sky ? 15 : 0);
    }

    private int lightNibble(boolean sky, int section, int packedIndex, int fallback) {
        long present = sky ? skyLightPresent : blockLightPresent;
        if ((present & (1L << section)) == 0L) return fallback;
        int layerBase = sky ? 0 : MAX_LIGHT_SECTIONS * LIGHT_SECTION_BYTES;
        int packed = lightData.get(layerBase + section * LIGHT_SECTION_BYTES
                + (packedIndex >>> 1)) & 0xff;
        return (packedIndex & 1) == 0 ? packed & 15 : packed >>> 4;
    }

    void setLight(boolean sky,int x,int y,int z,int value) {
        int section=(y>>>4)+1,base=(sky?0:MAX_LIGHT_SECTIONS*LIGHT_SECTION_BYTES)+section*LIGHT_SECTION_BYTES;
        long mask=1L<<section;
        if(((sky?skyLightPresent:blockLightPresent)&mask)==0) {
            for(int i=0;i<LIGHT_SECTION_BYTES;i+=8)lightData.putLong(base+i,sky?-1L:0L);
            if(sky)skyLightPresent|=mask;else blockLightPresent|=mask;
        }
        int cell=((y&15)<<8)|(z<<4)|x,offset=base+(cell>>>1),current=lightData.get(offset)&255;
        lightData.put(offset,(byte)((cell&1)==0?(current&240)|(value&15):(current&15)|((value&15)<<4)));
    }

    void beginBlockEntityUpdate() {
        blockEntityCount = 0;
    }

    void putBlockEntity(int localX, int worldY, int localZ, int typeId) {
        if ((localX & ~15) != 0 || (localZ & ~15) != 0) return;
        int packedPosition = localX | (localZ << 4) | ((worldY & 0xffff) << 8);
        for (int index = 0; index < blockEntityCount; index++) {
            int offset = index * 8;
            if (blockEntities.getInt(offset) == packedPosition) {
                blockEntities.putInt(offset + 4, typeId);
                return;
            }
        }
        if (blockEntityCount >= MAX_BLOCK_ENTITIES) return;
        int offset = blockEntityCount++ * 8;
        blockEntities.putInt(offset, packedPosition);
        blockEntities.putInt(offset + 4, typeId);
    }

    boolean exposeFullBlockFace(int x, int relativeY, int z, int faceMask) {
        int stateId = fullBlockState(x, relativeY, z);
        if (stateId <= 0 || faceMask == 0) return false;
        for (int offset = 0; offset < recordCount * RECORD_BYTES;
             offset += RECORD_BYTES) {
            int packed = records.getInt(offset);
            if (localX(packed) != x || localZ(packed) != z
                    || relativeY(packed) != relativeY) continue;
            int merged = packed | ((faceMask & 63) << 17);
            if (merged == packed) return false;
            records.putInt(offset, merged);
            revision++;
            return true;
        }
        if ((recordCount + 1) * RECORD_BYTES > records.capacity()) return false;
        int packed = x | (z << 4) | (relativeY << 8)
                | ((faceMask & 63) << 17);
        if (BlockStatePhysics.blocksMovement(stateId)) packed |= COLLIDABLE_FLAG;
        int offset = recordCount++ * RECORD_BYTES;
        records.putInt(offset, packed);
        // This is deliberately only a fallback surface until the texture/state
        // table is wired in; the important part is retaining real world depth.
        records.putInt(offset + Integer.BYTES, 0x76694fff);
        rebuildCollisionIndex();
        revision++;
        return true;
    }

    void rebuildSurfaceFromFullBlocks(int worldHeight, int preferredY) {
        recordCount = 0;
        java.util.Arrays.fill(collisionBits, 0L);
        int boundedHeight = Math.max(0, Math.min(512, worldHeight));
        int center = Math.max(0, Math.min(boundedHeight - 1, preferredY));
        for (int distance = 0; distance < boundedHeight; distance++) {
            int below = center - distance;
            if (below >= 0) emitFullSurfaceLayer(below, boundedHeight);
            int above = center + distance;
            if (distance != 0 && above < boundedHeight) {
                emitFullSurfaceLayer(above, boundedHeight);
            }
            if (recordCount * RECORD_BYTES >= records.capacity()) break;
        }
        rebuildCollisionIndex();
        revision++;
    }

    private void emitFullSurfaceLayer(int relativeY, int worldHeight) {
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int stateId = fullBlockState(x, relativeY, z);
                if (stateId <= 0) continue;
                int faces = 0;
                if (isFullAir(x, relativeY - 1, z, worldHeight)) faces |= 1 << 0;
                if (isFullAir(x, relativeY + 1, z, worldHeight)) faces |= 1 << 1;
                if (isFullAir(x, relativeY, z - 1, worldHeight)) faces |= 1 << 2;
                if (isFullAir(x, relativeY, z + 1, worldHeight)) faces |= 1 << 3;
                if (isFullAir(x - 1, relativeY, z, worldHeight)) faces |= 1 << 4;
                if (isFullAir(x + 1, relativeY, z, worldHeight)) faces |= 1 << 5;
                if (faces == 0) continue;
                if ((recordCount + 1) * RECORD_BYTES > records.capacity()) return;
                int packed = x | (z << 4) | (relativeY << 8)
                        | (faces << 17);
                if (BlockStatePhysics.blocksMovement(stateId)) {
                    packed |= COLLIDABLE_FLAG;
                }
                int offset = recordCount++ * RECORD_BYTES;
                records.putInt(offset, packed);
                records.putInt(offset + Integer.BYTES,
                        (faces & (1 << 1)) != 0 ? 0x58a84fff : 0x76694fff);
            }
        }
    }

    private boolean isFullAir(int x, int relativeY, int z, int worldHeight) {
        if (relativeY < 0 || relativeY >= worldHeight
                || x < 0 || x > 15 || z < 0 || z > 15) return true;
        return BlockStatePhysics.isAir(fullBlockState(x, relativeY, z));
    }

    private static int fullBlockIndex(int x, int relativeY, int z) {
        return relativeY * 256 + z * 16 + x;
    }

    public static int localX(int packedCell) {
        return packedCell & 15;
    }

    public static int localZ(int packedCell) {
        return (packedCell >>> 4) & 15;
    }

    public static int relativeY(int packedCell) {
        return (packedCell >>> 8) & 511;
    }

    public static int faceMask(int packedCell) {
        return (packedCell >>> 17) & 63;
    }

    public static int breakTicks(int packedCell) {
        return (packedCell >>> 24) & 0xff;
    }

    int breakTicksAt(int x, int relativeY, int z) {
        if ((x & ~15) != 0 || (z & ~15) != 0 || (relativeY & ~511) != 0) {
            return 0;
        }
        for (int offset = 0; offset < recordCount * RECORD_BYTES;
             offset += RECORD_BYTES) {
            int packed = records.getInt(offset);
            if (localX(packed) == x && localZ(packed) == z
                    && relativeY(packed) == relativeY) return breakTicks(packed);
        }
        return 0;
    }
}
