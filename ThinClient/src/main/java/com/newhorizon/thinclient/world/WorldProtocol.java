package com.newhorizon.thinclient.world;

import com.newhorizon.thinclient.protocol.BinaryCodec;
import com.newhorizon.thinclient.protocol.ProtocolException;
import com.newhorizon.thinclient.protocol.VarInts;

import java.nio.ByteBuffer;

/** Compact surface-only world stream emitted by the server bridge. */
public final class WorldProtocol {
    public static final String CHANNEL = "newhorizon:world_v1";
    public static final String HELLO_CHANNEL = "newhorizon:hello_v1";
    public static final int MAX_RECORDS_PER_CHUNK = 4_000;
    private static final int MAGIC = 0x4e485731; // NHW1
    private static final int VERSION = 1;
    private static final int RESET = 1;
    private static final int CHUNK_SURFACE = 2;
    private static final int REMOVE_CHUNK = 3;
    private static final int PLAYER_POSITION = 4;

    private WorldProtocol() {
    }

    public static void encodeHello(ByteBuffer output) {
        output.putInt(0x4e485431).put((byte) VERSION); // NHT1
    }

    public static WorldMessage decode(ByteBuffer payload) throws ProtocolException {
        BinaryCodec.require(payload, 6);
        if (payload.getInt() != MAGIC) throw new ProtocolException("Invalid world magic");
        int version = payload.get() & 0xff;
        if (version != VERSION) throw new ProtocolException("Unsupported world version " + version);
        int type = payload.get() & 0xff;
        WorldMessage result;
        switch (type) {
            case RESET:
                String dimension = BinaryCodec.readString(payload, 128);
                BinaryCodec.require(payload, 4);
                int minY = payload.getInt();
                int height = VarInts.read(payload);
                if (height <= 0 || height > 512) throw new ProtocolException("Invalid world height");
                result = new WorldMessage.Reset(dimension, minY, height);
                break;
            case CHUNK_SURFACE:
                BinaryCodec.require(payload, 12);
                int x = payload.getInt();
                int z = payload.getInt();
                int revision = payload.getInt();
                int count = VarInts.read(payload);
                if (count < 0 || count > MAX_RECORDS_PER_CHUNK) {
                    throw new ProtocolException("Invalid surface record count " + count);
                }
                int bytes = count * SurfaceChunk.RECORD_BYTES;
                BinaryCodec.require(payload, bytes);
                ByteBuffer records = payload.slice().asReadOnlyBuffer();
                records.limit(bytes);
                payload.position(payload.position() + bytes);
                result = new WorldMessage.ChunkSurface(x, z, revision, count, records);
                break;
            case REMOVE_CHUNK:
                BinaryCodec.require(payload, 8);
                result = new WorldMessage.RemoveChunk(payload.getInt(), payload.getInt());
                break;
            case PLAYER_POSITION:
                BinaryCodec.require(payload, 32);
                result = new WorldMessage.PlayerPosition(payload.getDouble(), payload.getDouble(),
                        payload.getDouble(), payload.getFloat(), payload.getFloat());
                break;
            default:
                throw new ProtocolException("Unknown world message " + type);
        }
        if (payload.hasRemaining()) throw new ProtocolException("Trailing world message bytes");
        return result;
    }

    public static void encodeReset(ByteBuffer output, WorldMessage.Reset reset) {
        header(output, RESET);
        BinaryCodec.writeString(output, reset.dimension, 128);
        output.putInt(reset.minY);
        VarInts.write(output, reset.height);
    }

    public static void encodeChunk(ByteBuffer output, int chunkX, int chunkZ,
                                   int revision, ByteBuffer records) {
        if ((records.remaining() % SurfaceChunk.RECORD_BYTES) != 0) {
            throw new IllegalArgumentException("unaligned records");
        }
        int count = records.remaining() / SurfaceChunk.RECORD_BYTES;
        if (count > MAX_RECORDS_PER_CHUNK) throw new IllegalArgumentException("too many records");
        header(output, CHUNK_SURFACE);
        output.putInt(chunkX).putInt(chunkZ).putInt(revision);
        VarInts.write(output, count);
        output.put(records.slice());
    }

    public static void encodeRemove(ByteBuffer output, int chunkX, int chunkZ) {
        header(output, REMOVE_CHUNK);
        output.putInt(chunkX).putInt(chunkZ);
    }

    public static void encodePlayerPosition(ByteBuffer output,
                                            WorldMessage.PlayerPosition position) {
        header(output, PLAYER_POSITION);
        output.putDouble(position.x).putDouble(position.y).putDouble(position.z);
        output.putFloat(position.yaw).putFloat(position.pitch);
    }

    private static void header(ByteBuffer output, int type) {
        output.putInt(MAGIC).put((byte) VERSION).put((byte) type);
    }
}


