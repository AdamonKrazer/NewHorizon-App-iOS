package com.newhorizon.thinclient.world;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/** Original 1.20.1 block textures and compact, offline-resolved model faces. */
public final class VanillaBlockTextures {
    public static final int TILE_MASK = 0xfff;
    public static final int TINT_NONE = 0, TINT_GRASS = 1, TINT_FOLIAGE = 2,
            TINT_WATER = 3, TINT_SPRUCE = 4, TINT_BIRCH = 5,
            TINT_REDSTONE = 6, TINT_SPECIAL = 7;
    public static final int CUTOUT = 1 << 17, TRANSLUCENT = 1 << 18,
            EMISSIVE = 1 << 19;
    private static final String ROOT = "/assets/newhorizon/block_textures/";
    private static final Catalog DATA = load();
    private VanillaBlockTextures() {}

    /** Faces: down, up, north, south, west, east. */
    public static int face(int state, int face) {
        return state >= 0 && state < DATA.states && face >= 0 && face < 6
                ? DATA.faces[state * 7 + face] : DATA.fallback;
    }
    public static int representative(int state) {
        return state >= 0 && state < DATA.states ? DATA.faces[state * 7 + 6] : DATA.fallback;
    }
    public static int tile(int descriptor) { return descriptor & TILE_MASK; }
    /** Quarter turns: 1 samples (1-v,u), 2 (1-u,1-v), 3 (v,1-u). */
    public static int rotation(int descriptor) { return descriptor >>> 12 & 3; }
    public static int tint(int descriptor) { return descriptor >>> 14 & 7; }
    public static boolean isCutout(int descriptor) { return (descriptor & CUTOUT) != 0; }
    public static boolean isTranslucent(int descriptor) { return (descriptor & TRANSLUCENT) != 0; }
    public static boolean isEmissive(int descriptor) { return (descriptor & EMISSIVE) != 0; }
    public static int stateCount() { return DATA.states; }
    public static int tileCount() { return DATA.tiles.length; }
    public static String tileResource(int id) { return ROOT + entry(id).name + ".png"; }
    public static String name(int id) { return entry(id).name; }
    public static int frameWidth(int id) { return entry(id).width; }
    public static int frameHeight(int id) { return entry(id).height; }
    public static int frameCount(int id) { return entry(id).frames; }
    public static int sequenceLength(int id) { return entry(id).indices.length; }
    public static int frameIndex(int id, int sequence) {
        Tile tile = entry(id); return tile.indices[Math.floorMod(sequence, tile.indices.length)];
    }
    public static int frameTime(int id, int sequence) {
        Tile tile = entry(id); return tile.times[Math.floorMod(sequence, tile.times.length)];
    }
    public static boolean interpolate(int id) { return entry(id).interpolate; }
    public static int tileId(String name) {
        if (name == null) return -1;
        if (name.startsWith("minecraft:")) name = name.substring(10);
        if (name.startsWith("block/")) name = name.substring(6);
        if (name.endsWith(".png")) name = name.substring(0, name.length() - 4);
        Integer id = DATA.ids.get(name); return id == null ? -1 : id;
    }
    public static int defaultState(String material) {
        if (material == null) return -1;
        material = material.toLowerCase(java.util.Locale.ROOT);
        if (material.startsWith("minecraft:")) material = material.substring(10);
        Integer id = DATA.defaults.get(material); return id == null ? -1 : id;
    }
    public static int descriptorForFluid(boolean lava, boolean flow) {
        int id = tileId((lava ? "lava_" : "water_") + (flow ? "flow" : "still"));
        return Math.max(0, id) | (lava ? EMISSIVE : TRANSLUCENT | TINT_WATER << 14);
    }
    private static Tile entry(int id) {
        return DATA.tiles[id >= 0 && id < DATA.tiles.length ? id : DATA.fallback & TILE_MASK];
    }
    private static Catalog load() {
        try (InputStream resource = VanillaBlockTextures.class.getResourceAsStream(ROOT + "catalog.bin")) {
            if (resource == null) throw new IOException("missing vanilla texture catalog");
            DataInputStream input = new DataInputStream(resource);
            if (input.readInt() != 0x4e485458 || input.readInt() != 1 || input.readInt() != 763)
                throw new IOException("vanilla texture catalog version");
            Catalog data = new Catalog();
            data.states = input.readInt(); int count = input.readInt(), defaults = input.readInt();
            if (data.states != 24135 || count < 1 || count > 4096 || defaults < 1 || defaults > 2048)
                throw new IOException("vanilla texture catalog bounds");
            data.tiles = new Tile[count];
            for (int i = 0; i < count; i++) {
                Tile tile = new Tile(); tile.name = input.readUTF();
                tile.width = input.readUnsignedShort(); tile.height = input.readUnsignedShort();
                tile.frames = input.readUnsignedShort(); tile.interpolate = input.readBoolean();
                int length = input.readUnsignedShort();
                if (tile.width < 1 || tile.width > 64 || tile.height < 1 || tile.height > 64
                        || tile.frames < 1 || tile.frames > 1024 || length < 1 || length > 1024)
                    throw new IOException("vanilla texture frame bounds");
                tile.indices = new int[length]; tile.times = new int[length];
                for (int j = 0; j < length; j++) {
                    tile.indices[j] = input.readUnsignedShort(); tile.times[j] = input.readUnsignedShort();
                    if (tile.indices[j] >= tile.frames || tile.times[j] < 1)
                        throw new IOException("vanilla texture animation bounds");
                }
                data.tiles[i] = tile; data.ids.put(tile.name, i);
            }
            data.faces = new int[data.states * 7];
            for (int i = 0; i < data.faces.length; i++) {
                int value = input.readInt();
                if ((value & TILE_MASK) >= count || (value & ~0xfffff) != 0)
                    throw new IOException("vanilla texture face bounds");
                data.faces[i] = value;
            }
            for (int i = 0; i < defaults; i++) data.defaults.put(input.readUTF(), input.readInt());
            if (input.read() != -1) throw new IOException("vanilla texture catalog trailing data");
            Integer stone = data.ids.get("stone"); data.fallback = stone == null ? 0 : stone;
            System.out.println("[NH-THIN] vanilla texture catalog states=" + data.states + " tiles=" + count);
            return data;
        } catch (IOException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }
    private static final class Catalog {
        int states, fallback; int[] faces; Tile[] tiles;
        final Map<String, Integer> ids = new HashMap<>(), defaults = new HashMap<>();
    }
    private static final class Tile {
        String name; int width, height, frames; boolean interpolate; int[] indices, times;
    }
}
