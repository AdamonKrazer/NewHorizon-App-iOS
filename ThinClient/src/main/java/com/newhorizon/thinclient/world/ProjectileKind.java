package com.newhorizon.thinclient.world;

/** IDs and motion constants from the bundled vanilla 1.20.1 registry/client. */
public final class ProjectileKind {
    public static final int ARROW=3, DRAGON_FIREBALL=22, EGG=24, PEARL=28,
            EXPERIENCE=33, FIREWORK=37, FIREBALL=57, SPIT=61, POTION=77,
            SHULKER=84, SMALL_FIREBALL=89, SNOWBALL=92, SPECTRAL_ARROW=94,
            TRIDENT=104, WITHER_SKULL=115, FISHING=123;
    private ProjectileKind() { }
    public static boolean isProjectile(int type) {
        switch (type) {
            case ARROW: case DRAGON_FIREBALL: case EGG: case PEARL:
            case EXPERIENCE: case FIREWORK: case FIREBALL: case SPIT: case POTION:
            case SHULKER: case SMALL_FIREBALL: case SNOWBALL: case SPECTRAL_ARROW:
            case TRIDENT: case WITHER_SKULL: case FISHING: return true;
            default: return false;
        }
    }
    public static boolean arrow(int type) {
        return type == ARROW || type == SPECTRAL_ARROW || type == TRIDENT;
    }
    public static boolean accelerating(int type) {
        return type == FIREBALL || type == SMALL_FIREBALL || type == DRAGON_FIREBALL
                || type == WITHER_SKULL;
    }
    public static boolean pickable(int type) {
        return type == FIREBALL || type == SHULKER;
    }
    public static double gravity(int type) {
        if (accelerating(type) || type == SHULKER || type == FIREWORK) return 0;
        if (type == EXPERIENCE) return (double) 0.07f;
        return arrow(type) || type == POTION ? (double) 0.05f
                : type == SPIT ? 0.06 : (double) 0.03f;
    }
    public static boolean chargedItem(String material) {
        material=materialKey(material);
        return "BOW".equals(material) || "CROSSBOW".equals(material)
                || "TRIDENT".equals(material) || "SHIELD".equals(material)
                || com.newhorizon.thinclient.inventory.ConsumableUse.durationTicks(material)>0;
    }
    /** These uses target a block before considering an offhand shield. */
    public static boolean blockUseItem(String material) {
        String key=materialKey(material);
        return "LEAD".equals(key)||"FIREWORK_ROCKET".equals(key)||"MINECART".equals(key)||"CHEST_MINECART".equals(key)
                ||"FURNACE_MINECART".equals(key)||"HOPPER_MINECART".equals(key)||"TNT_MINECART".equals(key)
                ||"COMMAND_BLOCK_MINECART".equals(key)||"RAIL".equals(key)||"POWERED_RAIL".equals(key)
                ||"DETECTOR_RAIL".equals(key)||"ACTIVATOR_RAIL".equals(key);
    }

    public static boolean airUseItem(String material) {
        material=materialKey(material);
        return ((!material.contains(":"))&&(material.endsWith("_BOAT")||material.endsWith("_RAFT"))) || chargedItem(material) || "EGG".equals(material)
                || "FISHING_ROD".equals(material) || "SNOWBALL".equals(material) || "ENDER_PEARL".equals(material)
                || "SPLASH_POTION".equals(material) || "LINGERING_POTION".equals(material)
                || "EXPERIENCE_BOTTLE".equals(material);
    }
    public static int leadHand(String main,String off) {
        return "LEAD".equals(materialKey(main))?0:"LEAD".equals(materialKey(off))?1:-1;
    }
    public static String materialKey(String material) {
        if(material==null) return "";
        if(material.startsWith("minecraft:")) material=material.substring(10);
        return material.toUpperCase(java.util.Locale.ROOT);
    }
}
