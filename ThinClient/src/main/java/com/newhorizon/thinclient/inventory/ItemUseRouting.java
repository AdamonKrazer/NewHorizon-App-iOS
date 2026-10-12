package com.newhorizon.thinclient.inventory;

import com.newhorizon.thinclient.world.ProjectileKind;

/** Conservative hand selection: send one action and leave acceptance to the server. */
public final class ItemUseRouting {
    private ItemUseRouting() { }
    /** IDs from the bundled official protocol-763 entity_type registry. */
    public static boolean entityBeforeAir(String material,int entityType) {
        if(AnimalFoodCatalog.accepts(entityType,material))return true;
        String key=ProjectileKind.materialKey(material);
        if("BUCKET".equals(key))return entityType==18||entityType==45||entityType==65; // cow, goat, mooshroom
        if(!"WATER_BUCKET".equals(key))return false;
        return entityType==4||entityType==16||entityType==78||entityType==81||entityType==99||entityType==105;
    }
    /** Attempt planting edible BlockItems before eating, only at a compatible support. */
    public static boolean blockBeforeAir(String material,com.newhorizon.thinclient.world.WorldBlockRaycast.Blocks world,
                                         int x,int y,int z,int face) {
        String key=ProjectileKind.materialKey(material);
        boolean crop="CARROT".equals(key)||"POTATO".equals(key),berry="SWEET_BERRIES".equals(key),vine="GLOW_BERRIES".equals(key);
        if(!crop&&!berry&&!vine||face<0||face>5)return false;
        int target=world.blockStateAt(x,y,z);
        if(!com.newhorizon.thinclient.world.BlockStateOutline.replaceable(target)) {
            if(face==0)y--;else if(face==1)y++;else if(face==2)z--;else if(face==3)z++;else if(face==4)x--;else x++;
        }
        if(!com.newhorizon.thinclient.world.BlockStateOutline.replaceable(world.blockStateAt(x,y,z)))return false;
        int support=world.blockStateAt(x,y+(vine?1:-1),z);
        String name=com.newhorizon.thinclient.audio.VanillaSoundCatalog.load().blockName(support);
        if(name==null)return false;
        if(crop)return name.equals("minecraft:farmland");
        if(vine)return name.equals("minecraft:cave_vines")||name.equals("minecraft:cave_vines_plant")
                ||com.newhorizon.thinclient.world.BlockStateOutline.supportsFromBelow(support);
        // Exact vanilla data/minecraft/tags/blocks/dirt.json, plus BushBlock's farmland support.
        switch(name) {
            case "minecraft:farmland":case "minecraft:coarse_dirt":case "minecraft:dirt":case "minecraft:grass_block":
            case "minecraft:moss_block":case "minecraft:mud":case "minecraft:muddy_mangrove_roots":case "minecraft:mycelium":
            case "minecraft:podzol":case "minecraft:rooted_dirt":return true;
            default:return false;
        }
    }
    public static int blockHand(String main,String off,boolean targetConsumesUse) {
        if(targetConsumesUse||ProjectileKind.airUseItem(main)||ProjectileKind.blockUseItem(main))return 0;
        return ProjectileKind.blockUseItem(off)||ItemInteractionCatalog.bucket(off)?1:0;
    }
}