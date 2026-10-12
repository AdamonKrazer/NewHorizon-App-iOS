package com.newhorizon.thinclient.inventory;

import com.newhorizon.thinclient.protocol.*;
import com.newhorizon.thinclient.world.ProjectileKind;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/** Regression cases for item routing and wire encoding, using the independent item ID registry. */
public final class ItemInteractionSelfTest {
    public static void main(String[] args) throws Exception {run();}
    public static void run() throws Exception {
        int count=0,blocks=0,actions=0,buckets=0;
        try(BufferedReader input=new BufferedReader(new InputStreamReader(ItemInteractionSelfTest.class.getResourceAsStream(
                "/assets/newhorizon/vanilla_item_ids_1_20_1.tsv"),StandardCharsets.UTF_8))) {
            for(String row;(row=input.readLine())!=null;) {
                if(row.isEmpty()||row.charAt(0)=='#')continue;
                String[] part=row.split("\\t");int id=Integer.parseInt(part[0]);String name=part[1];
                check(ItemInteractionCatalog.protocolId(name)==id,"registry ID for "+name);
                check(ItemInteractionCatalog.protocolId("minecraft:"+name)==id,"namespaced ID for "+name);
                if(ItemInteractionCatalog.blockItem(name))blocks++;
                if(ItemInteractionCatalog.blockAction(name))actions++;
                if(ItemInteractionCatalog.bucket(name))buckets++;
                count++;
            }
        }
        check(count==1255&&blocks==868&&actions==1007&&buckets==9,"complete official interaction classes");
        for(String name:new String[]{"TORCH","SOUL_TORCH","REDSTONE_TORCH","LANTERN","OAK_DOOR","OAK_SIGN","WHEAT_SEEDS","CARROT","POTATO"})
            check(ItemInteractionCatalog.blockItem(name)&&ProjectileKind.blockUseItem(name),"BlockItem priority: "+name);
        for(String name:new String[]{"BONE_MEAL","FLINT_AND_STEEL","FIRE_CHARGE","DIAMOND_AXE","DIAMOND_SHOVEL","DIAMOND_HOE","LEAD","FIREWORK_ROCKET","COW_SPAWN_EGG","ARMOR_STAND"})
            check(ProjectileKind.blockUseItem(name),"useOn override priority: "+name);
        check(!ProjectileKind.blockUseItem("DIAMOND_PICKAXE")&&!ProjectileKind.blockUseItem("STICK"),"items with no block action do not hide offhand");
        check(ItemUseRouting.blockHand("DIAMOND_PICKAXE","TORCH",false)==1,"pickaxe main + torch offhand");
        check(ItemUseRouting.blockHand("TORCH","SHIELD",false)==0,"torch main retains placement before shield");
        check(ItemUseRouting.blockHand("TORCH","BREAD",false)==0,"torch main retains placement before food");
        check(ItemUseRouting.blockHand("BOW","TORCH",false)==0,"bow air action retains main hand");
        check(ItemUseRouting.blockHand("DIAMOND_PICKAXE","TORCH",true)==0,"usable block takes main-hand interaction first");
        for(String name:new String[]{"BUCKET","WATER_BUCKET","LAVA_BUCKET","COD_BUCKET","SALMON_BUCKET","TROPICAL_FISH_BUCKET","PUFFERFISH_BUCKET","AXOLOTL_BUCKET","TADPOLE_BUCKET"})
            check(ProjectileKind.airUseItem(name)&&!ProjectileKind.blockUseItem(name),"fluid bucket uses server ray cast: "+name);
        check(ProjectileKind.blockUseItem("POWDER_SNOW_BUCKET")&&!ProjectileKind.airUseItem("POWDER_SNOW_BUCKET"),"solid bucket is a BlockItem, not fluid ray cast");
        check(ProjectileKind.chargedItem("MILK_BUCKET")&&!ItemInteractionCatalog.bucket("MILK_BUCKET"),"milk preserves held drinking");
        for(int type:new int[]{18,45,65})check(ItemUseRouting.entityBeforeAir("BUCKET",type),"bucket milks adult server-owned entity "+type);
        for(int type:new int[]{4,16,78,81,99,105})check(ItemUseRouting.entityBeforeAir("WATER_BUCKET",type),"water bucket catches server-owned entity "+type);
        check(!ItemUseRouting.entityBeforeAir("LAVA_BUCKET",18)&&!ItemUseRouting.entityBeforeAir("BUCKET",105)
                &&!ItemUseRouting.entityBeforeAir("other:bucket",18),"other fluid uses retain bucket ray cast");
        for(String name:new String[]{"CARROT","POTATO","BEETROOT"})check(ItemUseRouting.entityBeforeAir(name,72),"pig food before player eating "+name);
        check(ItemUseRouting.entityBeforeAir("COD",11)&&ItemUseRouting.entityBeforeAir("SALMON",11),"cat fish");
        check(ItemUseRouting.entityBeforeAir("ROTTEN_FLESH",116)&&!ItemUseRouting.entityBeforeAir("COD",116),"wolf meat, not fish");
        check(ItemUseRouting.entityBeforeAir("SWEET_BERRIES",38)&&ItemUseRouting.entityBeforeAir("GLOW_BERRIES",38),"official fox food tag");
        check(ItemUseRouting.entityBeforeAir("TROPICAL_FISH_BUCKET",4)&&ItemUseRouting.entityBeforeAir("COOKIE",70),"axolotl tag and parrot cookie interaction");
        check(ItemUseRouting.entityBeforeAir("DANDELION",6)&&ItemUseRouting.entityBeforeAir("APPLE",49),"bee flowers and horse apples");
        check(!ItemUseRouting.entityBeforeAir("BREAD",72)&&!ItemUseRouting.entityBeforeAir("CARROT",18),"unrelated foods still eat normally");
        com.newhorizon.thinclient.world.WorldBlockRaycastSelfTest.Grid soil=new com.newhorizon.thinclient.world.WorldBlockRaycastSelfTest.Grid().put(0,0,0,4286);
        check(ItemUseRouting.blockBeforeAir("CARROT",soil,0,0,0,1)&&ItemUseRouting.blockBeforeAir("POTATO",soil,0,0,0,1),"plant edible crops above farmland");
        check(!ItemUseRouting.blockBeforeAir("CARROT",soil,0,0,0,2),"farmland side without supporting soil does not plant");
        soil.put(0,1,0,1);check(!ItemUseRouting.blockBeforeAir("CARROT",soil,0,0,0,1),"occupied planting space returns to normal item use");
        soil=new com.newhorizon.thinclient.world.WorldBlockRaycastSelfTest.Grid().put(0,0,0,9);
        check(ItemUseRouting.blockBeforeAir("SWEET_BERRIES",soil,0,0,0,1)&&!ItemUseRouting.blockBeforeAir("CARROT",soil,0,0,0,1),"berry dirt tag differs from crop farmland");
        soil=new com.newhorizon.thinclient.world.WorldBlockRaycastSelfTest.Grid().put(0,1,0,1);
        check(ItemUseRouting.blockBeforeAir("GLOW_BERRIES",soil,0,1,0,0)&&!ItemUseRouting.blockBeforeAir("GLOW_BERRIES",soil,0,1,0,1),"glow berries hang below sturdy ceiling");
        check(!ProjectileKind.blockUseItem("other:torch")&&!ProjectileKind.airUseItem("other:water_bucket")
                &&ItemInteractionCatalog.protocolId("other:torch")==-1,"mod namespace isolation");
        for(int hand=0;hand<2;hand++) {
            ByteBuffer packet=ByteBuffer.allocate(64);
            MinecraftPackets.writeUseItemOn(packet,hand,-12,72,-1550,1,.5f,1,.25f,false,123);packet.flip();
            check(VarInts.read(packet)==0x31&&VarInts.read(packet)==hand,"useOn wire packet and hand");
            long pos=packet.getLong();check((int)(pos>>38)==-12&&(int)(pos<<26>>38)==-1550&&(int)(pos<<52>>52)==72,"signed block position");
            check(VarInts.read(packet)==1&&packet.getFloat()==.5f&&packet.getFloat()==1&&packet.getFloat()==.25f
                    &&packet.get()==0&&VarInts.read(packet)==123&&!packet.hasRemaining(),"useOn hit/sequence round trip");
        }
        boolean bad=false;try{MinecraftPackets.writeUseItemOn(ByteBuffer.allocate(64),2,0,0,0,1,0,0,0,false,0);}catch(IllegalArgumentException expected){bad=true;}
        check(bad,"invalid offhand rejected");
        ItemUseState use=new ItemUseState();ByteBuffer cooldown=ByteBuffer.allocate(16);
        VarInts.write(cooldown,ItemInteractionCatalog.protocolId("GOAT_HORN"));VarInts.write(cooldown,20);cooldown.flip();use.readCooldown(cooldown);
        check(use.coolingDown("GOAT_HORN",System.nanoTime())&&!use.coolingDown("TORCH",System.nanoTime()),"all registry cooldowns, not projectile whitelist");
        System.out.println("Item interaction tests passed: 1255 IDs, 868 BlockItems, 1007 useOn overrides, 9 fluid buckets, solid/milk exceptions and main/offhand packets");
    }
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
}