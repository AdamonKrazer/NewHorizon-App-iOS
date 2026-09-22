package com.newhorizon.thinclient.world;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Compact protocol-763 physics registry. The source data is produced by the
 * real 1.20.1 BlockState/VoxelShape implementations, but no Minecraft runtime
 * classes, models or textures are retained by the thin client.
 */
public final class BlockStatePhysics {
    public static final int WATER = 1;
    public static final int LAVA = 1 << 1;
    public static final int COBWEB = 1 << 2;
    public static final int CLIMBABLE = 1 << 3;
    public static final int NON_SOLID = 1 << 4;
    public static final int POWDER_SNOW = 1 << 5;
    public static final int SWEET_BERRY = 1 << 6;
    public static final int HONEY = 1 << 7;
    public static final int SOUL_SAND = 1 << 8;
    public static final int SLIME = 1 << 9;
    public static final int BUBBLE_UP = 1 << 10;
    public static final int BUBBLE_DOWN = 1 << 11;
    public static final int FLUID_FALLING = 1 << 12;

    private static final int MAGIC = 0x4e485048; // NHPH
    private static final int VERSION = 2;
    private static final int PROTOCOL = 763;
    private static final int STATE_BYTES = 6;
    private static final int MAX_STATES = 65_535;
    private static final Data DATA = load();

    private BlockStatePhysics() {
    }

    public static int flags(int stateId) {
        int offset = stateOffset(stateId);
        if (offset < 0) return 0;
        return (DATA.states[offset] & 0xff) << 8
                | DATA.states[offset + 1] & 0xff;
    }

    public static boolean blocksMovement(int stateId) {
        return collisionBoxCount(stateId) != 0;
    }

    /** True only when the vanilla collision shape covers the whole unit cube. */
    public static boolean fullyOccludesUnitCube(int stateId) {
        int shape = shapeIndex(stateId);
        if (shape < 0) return defaultBoxCount(stateId) != 0;
        return DATA.fullCubeShapes[shape];
    }

    public static boolean isAir(int stateId) {
        return stateId == 0 || stateId == 12_817 || stateId == 12_818;
    }

    /** FlowingFluid amount (1..8); its local surface is amount / 9. */
    public static int fluidAmount(int stateId) {
        int offset = stateOffset(stateId);
        return offset < 0 ? 0 : DATA.states[offset + 5] & 0xff;
    }

    public static float friction(int stateId) {
        return profile(stateId, 0, 0.6f);
    }

    public static float speedFactor(int stateId) {
        return profile(stateId, 1, 1.0f);
    }

    public static float jumpFactor(int stateId) {
        return profile(stateId, 2, 1.0f);
    }

    public static int collisionBoxCount(int stateId) {
        int shape = shapeIndex(stateId);
        return shape < 0 ? defaultBoxCount(stateId)
                : DATA.shapeOffsets[shape + 1] - DATA.shapeOffsets[shape];
    }

    /** Returns minX,minY,minZ,maxX,maxY,maxZ for one local collision box. */
    public static float collisionCoordinate(int stateId, int box, int coordinate) {
        int shape = shapeIndex(stateId);
        if (shape < 0) {
            if (box != 0 || coordinate < 0 || coordinate >= 6
                    || defaultBoxCount(stateId) == 0) throw new IndexOutOfBoundsException();
            return coordinate < 3 ? 0.0f : 1.0f;
        }
        int count = DATA.shapeOffsets[shape + 1] - DATA.shapeOffsets[shape];
        if (box < 0 || box >= count || coordinate < 0 || coordinate >= 6) {
            throw new IndexOutOfBoundsException();
        }
        return DATA.boxes[(DATA.shapeOffsets[shape] + box) * 6 + coordinate];
    }

    public static boolean intersectsCollision(int stateId, int blockX, int blockY,
                                               int blockZ, double minX, double minY,
                                               double minZ, double maxX, double maxY,
                                               double maxZ) {
        int boxes = collisionBoxCount(stateId);
        for (int box = 0; box < boxes; box++) {
            double boxMinX = blockX + collisionCoordinate(stateId, box, 0);
            double boxMinY = blockY + collisionCoordinate(stateId, box, 1);
            double boxMinZ = blockZ + collisionCoordinate(stateId, box, 2);
            double boxMaxX = blockX + collisionCoordinate(stateId, box, 3);
            double boxMaxY = blockY + collisionCoordinate(stateId, box, 4);
            double boxMaxZ = blockZ + collisionCoordinate(stateId, box, 5);
            if (maxX > boxMinX && minX < boxMaxX
                    && maxY > boxMinY && minY < boxMaxY
                    && maxZ > boxMinZ && minZ < boxMaxZ) return true;
        }
        return false;
    }

    private static int stateOffset(int stateId) {
        return stateId >= 0 && stateId < DATA.stateCount ? stateId * STATE_BYTES : -1;
    }

    private static int shapeIndex(int stateId) {
        int offset = stateOffset(stateId);
        if (offset < 0) return -1;
        return (DATA.states[offset + 2] & 0xff) << 8
                | DATA.states[offset + 3] & 0xff;
    }

    private static int defaultBoxCount(int stateId) {
        return stateId != 0 && !isAir(stateId) ? 1 : 0;
    }

    private static float profile(int stateId, int component, float fallback) {
        int offset = stateOffset(stateId);
        if (offset < 0) return fallback;
        int index = DATA.states[offset + 4] & 0xff;
        int profileOffset = index * 3 + component;
        return profileOffset < DATA.profiles.length
                ? DATA.profiles[profileOffset] : fallback;
    }

    private static Data load() {
        try (InputStream resource = BlockStatePhysics.class.getResourceAsStream(
                "/assets/newhorizon/block_physics_1_20_1.bin")) {
            if (resource == null) throw new IOException("missing physics table");
            DataInputStream input = new DataInputStream(resource);
            if (input.readInt() != MAGIC || input.readInt() != VERSION
                    || input.readInt() != PROTOCOL) {
                throw new IOException("physics table version");
            }
            int stateCount = input.readInt();
            int shapeCount = input.readInt();
            int profileCount = input.readInt();
            int boxCount = input.readInt();
            if (stateCount <= 0 || stateCount > MAX_STATES || shapeCount <= 0
                    || shapeCount >= 0xffff || profileCount <= 0
                    || profileCount > 0xff || boxCount < 0) {
                throw new IOException("invalid physics table dimensions");
            }
            byte[] states = new byte[stateCount * STATE_BYTES];
            input.readFully(states);
            int[] shapeOffsets = new int[shapeCount + 1];
            for (int index = 0; index < shapeOffsets.length; index++) {
                shapeOffsets[index] = input.readInt();
                if (shapeOffsets[index] < 0 || shapeOffsets[index] > boxCount
                        || index > 0 && shapeOffsets[index] < shapeOffsets[index - 1]) {
                    throw new IOException("invalid collision shape offset");
                }
            }
            if (shapeOffsets[shapeCount] != boxCount) {
                throw new IOException("collision box count mismatch");
            }
            float[] boxes = new float[boxCount * 6];
            for (int index = 0; index < boxes.length; index++) boxes[index] = input.readFloat();
            float[] profiles = new float[profileCount * 3];
            for (int index = 0; index < profiles.length; index++) {
                profiles[index] = input.readFloat();
            }
            if (input.read() != -1) throw new IOException("physics table trailing bytes");
            System.out.println("[NH-THIN] vanilla physics states=" + stateCount
                    + " shapes=" + shapeCount + " boxes=" + boxCount
                    + " profiles=" + profileCount);
            return new Data(stateCount, states, shapeOffsets, boxes, profiles);
        } catch (IOException exception) {
            System.out.println("[NH-THIN] vanilla physics fallback: "
                    + exception.getMessage());
            return fallback();
        }
    }

    private static Data fallback() {
        int stateCount = 24_162;
        byte[] states = new byte[stateCount * STATE_BYTES];
        // shape 0 is empty; shape 1 is a full block.
        for (int state = 1; state < stateCount; state++) {
            int offset = state * STATE_BYTES;
            states[offset + 3] = 1;
        }
        markFallback(states, 0, 0, NON_SOLID, 0, 0);
        markFallback(states, 80, 95, WATER | NON_SOLID, 0, 8);
        markFallback(states, 96, 111, LAVA | NON_SOLID, 0, 8);
        markFallback(states, 2_004, 2_004, COBWEB | NON_SOLID, 0, 0);
        markFallback(states, 12_817, 12_818, NON_SOLID, 0, 0);
        markFallback(states, 12_819, 12_819,
                WATER | NON_SOLID | BUBBLE_DOWN, 0, 8);
        markFallback(states, 12_820, 12_820,
                WATER | NON_SOLID | BUBBLE_UP, 0, 8);
        return new Data(stateCount, states, new int[]{0, 0, 1},
                new float[]{0, 0, 0, 1, 1, 1},
                new float[]{0.6f, 1.0f, 1.0f});
    }

    private static void markFallback(byte[] states, int first, int last,
                                     int flags, int shape, int amount) {
        for (int state = first; state <= last; state++) {
            int offset = state * STATE_BYTES;
            states[offset] = (byte) (flags >>> 8);
            states[offset + 1] = (byte) flags;
            states[offset + 2] = (byte) (shape >>> 8);
            states[offset + 3] = (byte) shape;
            states[offset + 5] = (byte) amount;
        }
    }

    private static final class Data {
        final int stateCount;
        final byte[] states;
        final int[] shapeOffsets;
        final float[] boxes;
        final float[] profiles;
        final boolean[] fullCubeShapes;

        Data(int stateCount, byte[] states, int[] shapeOffsets,
             float[] boxes, float[] profiles) {
            this.stateCount = stateCount;
            this.states = states;
            this.shapeOffsets = shapeOffsets;
            this.boxes = boxes;
            this.profiles = profiles;
            fullCubeShapes = new boolean[shapeOffsets.length - 1];
            for (int shape = 0; shape < fullCubeShapes.length; shape++) {
                int first = shapeOffsets[shape];
                if (shapeOffsets[shape + 1] - first != 1) continue;
                int offset = first * 6;
                fullCubeShapes[shape] = boxes[offset] <= 0.0f
                        && boxes[offset + 1] <= 0.0f
                        && boxes[offset + 2] <= 0.0f
                        && boxes[offset + 3] >= 1.0f
                        && boxes[offset + 4] >= 1.0f
                        && boxes[offset + 5] >= 1.0f;
            }
        }
    }
}
