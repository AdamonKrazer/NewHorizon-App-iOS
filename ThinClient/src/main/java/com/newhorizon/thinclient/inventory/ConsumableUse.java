package com.newhorizon.thinclient.inventory;

import com.newhorizon.thinclient.world.ProjectileKind;

/** Vanilla 1.20.1 Foods, Item.getUseDuration and ItemInHandRenderer.applyEatTransform.
 * Only presentation/input rules: the server completes use and changes the stack/food. */
public final class ConsumableUse {
    private ConsumableUse() { }
    public static int durationTicks(String material) {
        switch(ProjectileKind.materialKey(material)) {
            case "DRIED_KELP": return 16;
            case "HONEY_BOTTLE": return 40;
            case "POTION": case "MILK_BUCKET":
            case "APPLE": case "BAKED_POTATO": case "BEEF": case "BEETROOT":
            case "BEETROOT_SOUP": case "BREAD": case "CARROT": case "CHICKEN":
            case "CHORUS_FRUIT": case "COD": case "COOKED_BEEF": case "COOKED_CHICKEN":
            case "COOKED_COD": case "COOKED_MUTTON": case "COOKED_PORKCHOP":
            case "COOKED_RABBIT": case "COOKED_SALMON": case "COOKIE":
            case "ENCHANTED_GOLDEN_APPLE": case "GOLDEN_APPLE": case "GOLDEN_CARROT":
            case "MELON_SLICE": case "MUSHROOM_STEW": case "MUTTON": case "POISONOUS_POTATO":
            case "PORKCHOP": case "POTATO": case "PUFFERFISH": case "PUMPKIN_PIE":
            case "RABBIT": case "RABBIT_STEW": case "ROTTEN_FLESH": case "SALMON":
            case "SPIDER_EYE": case "SUSPICIOUS_STEW": case "SWEET_BERRIES":
            case "GLOW_BERRIES": case "TROPICAL_FISH": return 32;
            default: return 0;
        }
    }
    public static boolean drink(String material) {
        String key=ProjectileKind.materialKey(material);
        return key.equals("POTION")||key.equals("MILK_BUCKET")||key.equals("HONEY_BOTTLE");
    }
    public static boolean canStart(String material,int food,boolean creative) {
        if(durationTicks(material)==0)return false;
        String key=ProjectileKind.materialKey(material);
        return food<20||creative||drink(key)||key.equals("GOLDEN_APPLE")
                ||key.equals("ENCHANTED_GOLDEN_APPLE")||key.equals("CHORUS_FRUIT");
    }
    public static float remaining(int duration,long elapsedNanos) {
        return Math.max(0,Math.min(duration,duration-Math.max(0,elapsedNanos)/50_000_000f));
    }
    public static float blend(int duration,float remaining) {
        return duration<=0?0:1-(float)Math.pow(Math.max(0,Math.min(1,remaining/duration)),27);
    }
    public static float bob(int duration,float remaining) {
        return duration<=0||remaining/duration>=.8f?0:Math.abs((float)Math.cos(remaining/4*Math.PI)*.1f);
    }
}
