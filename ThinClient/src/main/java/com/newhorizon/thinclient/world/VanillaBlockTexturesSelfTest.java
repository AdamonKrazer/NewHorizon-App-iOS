package com.newhorizon.thinclient.world;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;

/** Coverage and resource integrity checks for the offline vanilla texture catalog. */
public final class VanillaBlockTexturesSelfTest {
    private VanillaBlockTexturesSelfTest() {}
    public static void run() throws IOException {
        require(VanillaBlockTextures.stateCount() == 24135, "complete protocol 763 state range");
        require(VanillaBlockTextures.tileCount() >= 928 && VanillaBlockTextures.tileCount() <= 2048,
                "bounded original texture catalog");
        for (int state = 0; state < VanillaBlockTextures.stateCount(); state++) {
            for (int face = 0; face < 6; face++) {
                int descriptor = VanillaBlockTextures.face(state, face);
                require(VanillaBlockTextures.tile(descriptor) < VanillaBlockTextures.tileCount(), "state texture bounds");
                require((descriptor & ~0xfffff) == 0, "packed descriptor fits terrain vertex");
            }
        }
        int animated = 0;
        for (int tile = 0; tile < VanillaBlockTextures.tileCount(); tile++) {
            try (InputStream resource = VanillaBlockTexturesSelfTest.class.getResourceAsStream(VanillaBlockTextures.tileResource(tile))) {
                require(resource != null, "original PNG exists: " + VanillaBlockTextures.name(tile));
                DataInputStream png = new DataInputStream(resource);
                require(png.readLong() == 0x89504e470d0a1a0aL, "PNG signature");
                require(png.readInt() == 13 && png.readInt() == 0x49484452, "PNG header");
                int width = png.readInt(), height = png.readInt();
                require(width % VanillaBlockTextures.frameWidth(tile) == 0
                        && height % VanillaBlockTextures.frameHeight(tile) == 0, "whole animation frames");
                require(width / VanillaBlockTextures.frameWidth(tile) * (height / VanillaBlockTextures.frameHeight(tile))
                        == VanillaBlockTextures.frameCount(tile), "frame count matches original strip");
            }
            int sequence = VanillaBlockTextures.sequenceLength(tile);
            require(sequence > 0, "nonempty animation sequence");
            for (int frame = 0; frame < sequence; frame++) {
                require(VanillaBlockTextures.frameIndex(tile, frame) < VanillaBlockTextures.frameCount(tile), "valid frame index");
                require(VanillaBlockTextures.frameTime(tile, frame) > 0, "positive frame duration");
            }
            if (VanillaBlockTextures.frameCount(tile) > 1) animated++;
        }
        require(animated == 49, "all original animated block textures");
        int grass = VanillaBlockTextures.defaultState("minecraft:grass_block");
        requireTile(grass, 0, "dirt");
        requireTile(grass, 1, "grass_block_top");
        requireTile(grass, 2, "grass_block_side");
        require(VanillaBlockTextures.tint(VanillaBlockTextures.face(grass, 1)) == VanillaBlockTextures.TINT_GRASS,
                "grass top biome tint");
        require(VanillaBlockTextures.tint(VanillaBlockTextures.face(grass, 2)) == 0,
                "grass dirt side is not recolored with overlay");
        require(!VanillaBlockTextures.isCutout(VanillaBlockTextures.face(grass, 2)),
                "opaque grass dirt side preserves neighbor occlusion");
        require(!VanillaBlockTextures.isCutout(VanillaBlockTextures.face(VanillaBlockTextures.defaultState("stone"), 1)),
                "opaque stone is not a cutout");
        require(VanillaBlockTextures.isCutout(VanillaBlockTextures.face(VanillaBlockTextures.defaultState("oak_leaves"), 1)),
                "leaves preserve original transparent texels");
        int furnace = VanillaBlockTextures.defaultState("FURNACE");
        requireTile(furnace, 1, "furnace_top");
        requireTile(furnace, 2, "furnace_front");
        requireTile(furnace, 3, "furnace_side");
        int log = VanillaBlockTextures.defaultState("oak_log");
        requireTile(log, 1, "oak_log_top"); requireTile(log, 2, "oak_log");
        requireTile(130, 4, "oak_log_top"); requireTile(130, 1, "oak_log");
        requireTile(132, 2, "oak_log_top"); requireTile(132, 1, "oak_log");
        requireTile(4297, 3, "furnace_front"); requireTile(4299, 4, "furnace_front");
        requireTile(4301, 5, "furnace_front");
        int redBed = VanillaBlockTextures.defaultState("red_bed");
        require(VanillaBlockTextures.name(VanillaBlockTextures.tile(VanillaBlockTextures.face(redBed, 1)))
                .startsWith("builtin/bed_red_foot_"), "bed foot uses original bedding UV, not wood particle");
        require(VanillaBlockTextures.tile(VanillaBlockTextures.face(1914, 1))
                != VanillaBlockTextures.tile(VanillaBlockTextures.face(1915, 1)), "bed head preserves pillow distinction");
        for (String material : new String[]{"chest", "trapped_chest", "ender_chest", "red_shulker_box",
                "oak_sign", "oak_hanging_sign", "blue_banner", "piglin_head", "conduit", "end_portal"}) {
            int state = VanillaBlockTextures.defaultState(material);
            require(VanillaBlockTextures.name(VanillaBlockTextures.tile(VanillaBlockTextures.face(state, 2)))
                    .startsWith("builtin/"), "built-in model original texture: " + material);
        }
        for (String material : new String[]{"redstone_wire", "melon_stem", "pumpkin_stem", "attached_melon_stem", "lily_pad"}) {
            int descriptor = VanillaBlockTextures.representative(VanillaBlockTextures.defaultState(material));
            require(VanillaBlockTextures.tint(descriptor) == 0, "state-specific vanilla tint baked once: " + material);
            require(VanillaBlockTextures.name(VanillaBlockTextures.tile(descriptor)).startsWith("builtin/tint/"),
                    "state-specific original pixels: " + material);
        }
        require(VanillaBlockTextures.defaultState("not_a_block") == -1, "unknown held item not a block");
        require(VanillaBlockTextures.tileId("minecraft:block/water_flow.png") == VanillaBlockTextures.tileId("water_flow"),
                "canonical material lookup");
        int lava = VanillaBlockTextures.tileId("lava_still");
        require(VanillaBlockTextures.sequenceLength(lava) == 38 && VanillaBlockTextures.frameTime(lava, 0) == 2,
                "vanilla lava forward/reverse frame sequence");
        require(VanillaBlockTextures.frameIndex(lava, 19) == 19 && VanillaBlockTextures.frameIndex(lava, 20) == 18,
                "lava sequence turns around");
        require(VanillaBlockTextures.frameWidth(VanillaBlockTextures.tileId("water_flow")) == 32,
                "flow texture preserves native 32 pixels");
        require(VanillaBlockTextures.interpolate(VanillaBlockTextures.tileId("prismarine")), "prismarine interpolation");
        System.out.println("Vanilla block texture tests passed: 24135 states, "
                + VanillaBlockTextures.tileCount() + " PNGs, 49 animations, model directions and tint.");
    }
    private static void requireTile(int state, int face, String texture) {
        require(VanillaBlockTextures.tile(VanillaBlockTextures.face(state, face)) == VanillaBlockTextures.tileId(texture),
                "vanilla face " + texture);
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
