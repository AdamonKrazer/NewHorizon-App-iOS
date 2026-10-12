package com.newhorizon.thinclient.audio;

import com.newhorizon.thinclient.world.DebugCatalog;

/** Local BlockSetType/WoodType sound branches from vanilla 1.20.1. */
final class BlockInteractionSounds {
    private BlockInteractionSounds() { }

    static boolean canOpenByHand(int state) {
        String name = VanillaSoundCatalog.load().blockName(state);
        return name != null && name.startsWith("minecraft:") && !name.startsWith("minecraft:iron_")
                && (name.endsWith("_door") || name.endsWith("_trapdoor") || name.endsWith("_fence_gate"));
    }

    static String changed(int before, int after) {
        VanillaSoundCatalog catalog = VanillaSoundCatalog.load();
        String name = catalog.blockName(before);
        if (!canOpenByHand(before) || !name.equals(catalog.blockName(after))) return null;
        String oldOpen = property(before, "open"), newOpen = property(after, "open");
        if (oldOpen.isEmpty() || newOpen.isEmpty() || oldOpen.equals(newOpen)) return null;
        String family = name.startsWith("minecraft:cherry_") ? "cherry_wood_"
                : name.startsWith("minecraft:bamboo_") ? "bamboo_wood_"
                : name.startsWith("minecraft:crimson_") || name.startsWith("minecraft:warped_") ? "nether_wood_"
                : name.endsWith("_fence_gate") ? "" : "wooden_";
        String type = name.endsWith("_fence_gate") ? "fence_gate"
                : name.endsWith("_trapdoor") ? "trapdoor" : "door";
        return "minecraft:block." + family + type + ("true".equals(newOpen) ? ".open" : ".close");
    }

    static boolean placed(int before, int after, String expected) {
        VanillaSoundCatalog catalog = VanillaSoundCatalog.load();
        if (!expected.equals(catalog.blockName(after))) return false;
        if (!expected.equals(catalog.blockName(before))) return true;
        // The same block changing properties is not placement, except stack growth.
        if (expected.endsWith("_slab"))
            return !"double".equals(property(before, "type")) && "double".equals(property(after, "type"));
        String count = expected.equals("minecraft:snow") ? "layers"
                : expected.equals("minecraft:candle") || expected.endsWith("_candle") ? "candles"
                : expected.equals("minecraft:sea_pickle") ? "pickles"
                : expected.equals("minecraft:turtle_egg") ? "eggs"
                : expected.equals("minecraft:pink_petals") ? "flower_amount" : null;
        if (count == null) return false;
        String a = property(before, count), b = property(after, count);
        return a.length() == 1 && b.length() == 1 && b.charAt(0) > a.charAt(0);
    }

    private static String property(int state, String key) {
        String properties = DebugCatalog.get().properties(state), prefix = key + ": ";
        int start = properties.indexOf(prefix);
        if (start < 0 || start > 0 && properties.charAt(start - 1) != '\n') return "";
        start += prefix.length();
        int end = properties.indexOf('\n', start);
        return properties.substring(start, end < 0 ? properties.length() : end);
    }
}
