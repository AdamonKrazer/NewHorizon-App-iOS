package com.newhorizon.thinclient.world;

import java.nio.ByteBuffer;

public abstract class WorldMessage {
    private WorldMessage() {
    }

    public static final class Reset extends WorldMessage {
        public final String dimension;
        public final int minY;
        public final int height;

        public Reset(String dimension, int minY, int height) {
            this.dimension = dimension;
            this.minY = minY;
            this.height = height;
        }
    }

    public static final class ChunkSurface extends WorldMessage {
        public final int chunkX;
        public final int chunkZ;
        public final int revision;
        public final int recordCount;
        /** Packet-backed view; the store must consume it synchronously. */
        public final ByteBuffer records;

        ChunkSurface(int chunkX, int chunkZ, int revision, int recordCount,
                     ByteBuffer records) {
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            this.revision = revision;
            this.recordCount = recordCount;
            this.records = records;
        }
    }

    public static final class RemoveChunk extends WorldMessage {
        public final int chunkX;
        public final int chunkZ;

        public RemoveChunk(int chunkX, int chunkZ) {
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
        }
    }

    public static final class PlayerPosition extends WorldMessage {
        public final double x;
        public final double y;
        public final double z;
        public final float yaw;
        public final float pitch;

        public PlayerPosition(double x, double y, double z, float yaw, float pitch) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.yaw = yaw;
            this.pitch = pitch;
        }
    }
}


