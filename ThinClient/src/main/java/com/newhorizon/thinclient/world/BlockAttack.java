package com.newhorizon.thinclient.world;

/** Fire is extinguished by START_DESTROY, never put into timed block mining. */
public final class BlockAttack {
    private BlockAttack() {}
    public static boolean isFire(int state) {
        String block=DebugCatalog.get().block(state);
        return "minecraft:fire".equals(block)||"minecraft:soul_fire".equals(block);
    }
}
