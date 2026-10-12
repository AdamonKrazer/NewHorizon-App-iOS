package com.newhorizon.thinclient.world;

import com.newhorizon.thinclient.protocol.BinaryCodec;
import com.newhorizon.thinclient.protocol.NbtSkipper;
import com.newhorizon.thinclient.protocol.ProtocolException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/** Dimension geometry arrives in Login/Respawn before its first chunk, not in the bridge reset. */
public final class DimensionState {
    private Map<String, Type> types = new HashMap<>();
    private volatile Type current;
    private volatile long revision;
    private boolean awaitingTeleport, loading;

    public long revision() { return revision; }
    public boolean active() { return current != null; }
    public String effects() { Type t = current; return t == null ? "minecraft:overworld" : t.effects; }

    public synchronized void readRegistry(ByteBuffer input) throws ProtocolException {
        if (type(input) != 10) throw new ProtocolException("Expected dimension registry compound");
        string(input);
        Map<String, Type> next = new HashMap<>();
        while (true) {
            int tag = type(input); if (tag == 0) break;
            String name = string(input);
            if (tag == 10 && "minecraft:dimension_type".equals(name)) readTypes(input, next);
            else NbtSkipper.skipUnnamed(input, tag);
        }
        types = next;
    }

    public synchronized void enter(String type, String world, WorldChunkStore chunks,
                                   EnvironmentState environment, boolean login) throws ProtocolException {
        Type next = types.get(type);
        if (next == null) throw new ProtocolException("Unknown dimension type " + type);
        boolean changed = login || current != next || !world.equals(chunks.dimension());
        if (changed) chunks.enterDimension(world, next.minY, next.height);
        current = next;
        environment.changeDimension(next.fixedTime);
        awaitingTeleport = loading = true;
        revision++;
        System.out.println("[NH-DIM] enter world=" + world + " type=" + type + " minY=" + next.minY
                + " height=" + next.height + " effects=" + next.effects + " reload=" + changed);
    }

    public synchronized void teleported() { awaitingTeleport = false; }
    public synchronized boolean awaitingTeleport() { return awaitingTeleport; }
    public synchronized boolean loading() { return loading; }
    public synchronized boolean ready(WorldChunkStore chunks, double x, double y, double z) {
        if (!loading) return true;
        if (awaitingTeleport || current == null) return false;
        int by = Math.max(current.minY, Math.min(current.minY + current.height - 1, (int)Math.floor(y)));
        if (!chunks.hasFullBlockData((int)Math.floor(x), by, (int)Math.floor(z))) return false;
        loading = false;
        System.out.println("[NH-DIM] destination ready " + chunks.dimension());
        return true;
    }

    private static void readTypes(ByteBuffer in, Map<String, Type> types) throws ProtocolException {
        while (true) {
            int tag = type(in); if (tag == 0) return;
            String name = string(in);
            if (tag == 9 && "value".equals(name)) {
                BinaryCodec.require(in, 5); int child = in.get() & 255, count = in.getInt();
                if (child != 10 || count < 0 || count > 128) throw new ProtocolException("Dimension registry limit");
                for (int i = 0; i < count; i++) {
                    Type value = new Type(); readEntry(in, "", value, 0);
                    if (value.name == null || value.effects == null || value.minY % 16 != 0
                            || value.height < 16 || value.height > 512 || value.height % 16 != 0
                            || value.minY < -2048 || value.minY + value.height > 2048)
                        throw new ProtocolException("Unsupported dimension geometry " + value.name);
                    types.put(value.name, value);
                }
            } else NbtSkipper.skipUnnamed(in, tag);
        }
    }

    private static void readEntry(ByteBuffer in, String prefix, Type result, int depth) throws ProtocolException {
        if (depth > 8) throw new ProtocolException("Dimension NBT depth");
        while (true) {
            int tag = type(in); if (tag == 0) return;
            String key = prefix + string(in);
            if (tag == 10) readEntry(in, key + ".", result, depth + 1);
            else if (tag == 8) {
                String value = string(in);
                if (key.equals("name")) result.name = value;
                if (key.equals("element.effects")) result.effects = value;
            } else if (tag == 3) {
                BinaryCodec.require(in, 4); int value = in.getInt();
                if (key.equals("element.min_y")) result.minY = value;
                if (key.equals("element.height")) result.height = value;
            } else if (tag == 4 && key.equals("element.fixed_time")) {
                BinaryCodec.require(in, 8); result.fixedTime = in.getLong();
            } else NbtSkipper.skipUnnamed(in, tag);
        }
    }
    private static int type(ByteBuffer in) throws ProtocolException {
        BinaryCodec.require(in, 1); int type = in.get() & 255;
        if (type > 12) throw new ProtocolException("Invalid dimension NBT type"); return type;
    }
    private static String string(ByteBuffer in) throws ProtocolException {
        BinaryCodec.require(in, 2); int length = in.getShort() & 65535;
        if (length > 1024) throw new ProtocolException("Dimension string limit");
        BinaryCodec.require(in, length); byte[] bytes = new byte[length]; in.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }
    private static final class Type {
        String name, effects;
        int minY = Integer.MIN_VALUE, height;
        Long fixedTime;
    }
}
