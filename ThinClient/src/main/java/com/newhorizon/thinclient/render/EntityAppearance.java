package com.newhorizon.thinclient.render;

import com.newhorizon.thinclient.world.EntityTracker;

/** Protocol 763 entity skins; family variants retain the server's metadata. */
final class EntityAppearance {
    private static final String[] BOATS={"oak","spruce","birch","jungle","acacia","dark_oak","mangrove","bamboo","cherry"};
    private static final String[] HORSES={"white","creamy","chestnut","brown","black","gray","darkbrown"};
    private static final String[] CATS={"tabby","black","red","siamese","british_shorthair","calico","persian","ragdoll","white","jellie","all_black"};
    private static final String[] AXOLOTLS={"lucy","wild","gold","cyan","blue"};
    private static final String[] PARROTS={"red_blue","blue","green","yellow_blue","grey"};
    private static final String[] RABBITS={"brown","white","black","white_splotched","gold","salt"};
    private static final String[] COLORS={"white","orange","magenta","light_blue","yellow","lime","pink","gray","light_gray","cyan","purple","blue","brown","green","red","black"};
    private static final String[] VILLAGERS={"desert","jungle","plains","savanna","snow","swamp","taiga"};
    private static final String[] PROFESSIONS={"","armorer","butcher","cartographer","cleric","farmer","fisherman","fletcher","leatherworker","librarian","mason","nitwit","shepherd","toolsmith","weaponsmith"};
    private static final String[] HORSE_MARKINGS={"","white","whitefield","whitedots","blackdots"};
    private static final String[] FROGS={"temperate_frog","warm_frog","cold_frog"};
    private static final String[] LLAMAS={"creamy","white","brown","gray"};
    private static final String[] PANDAS={"panda","lazy_panda","worried_panda","playful_panda","brown_panda","weak_panda","aggressive_panda"};
    private static final int[] DYES={0xe6e6e6,0xd87f33,0xb24cd8,0x6699d8,0xe5e533,0x7fcc19,0xf27fa5,0x4c4c4c,0x999999,0x4c7f99,0x7f3fb2,0x334cb2,0x664c33,0x667f33,0x993333,0x191919};
    private static String variant(String[] values,int n) {return values[Math.max(0,Math.min(values.length-1,n))];}
    static int skin(EntityTracker.Renderable e) {return EntitySkins.find(name(e));}
    static String name(EntityTracker.Renderable e) {
        if(e.player||e.type==122)return "player/wide/steve";
        int[] a=e.appearance;
        switch(e.type) {
            case 0:return "allay/allay";case 2:return "armorstand/wood";
            case 4:return "axolotl/axolotl_"+variant(AXOLOTLS,a[17]);case 5:return "bat";
            case 6:return "bee/bee"+(a[18]>0?"_angry":"")+((a[17]&8)!=0?"_nectar":"");case 7:return "blaze";
            case 9:case 13:return (e.type==9?"boat/":"chest_boat/")+variant(BOATS,a[11]);
            case 10:return "camel/camel";case 11:return "cat/"+variant(CATS,a[19]);
            case 12:return "spider/cave_spider";case 15:return "chicken";case 16:return "fish/cod";
            case 18:return "cow/cow";case 19:return "creeper/creeper";case 20:return "dolphin";
            case 21:return "horse/donkey";case 23:return "zombie/drowned";case 25:return "guardian_elder";
            case 26:return "end_crystal/end_crystal";case 27:return "enderdragon/dragon";
            case 29:return "enderman/enderman";case 30:return "endermite";case 31:return "illager/evoker";
            case 32:return "illager/evoker_fangs";case 34:return "experience_orb";
            case 38:return "fox/"+(a[17]==1?"snow_fox":"fox");
            case 39:return "frog/"+variant(FROGS,a[17]);
            case 41:return a[16]!=0?"ghast/ghast_shooting":"ghast/ghast";case 42:case 118:return "zombie/zombie";
            case 43:return "item/glow_item_frame";case 44:return "squid/glow_squid";case 45:return "goat/goat";
            case 46:return "guardian";case 47:return "hoglin/hoglin";
            case 49:return "horse/horse_"+variant(HORSES,a[18]&255);case 50:return "zombie/husk";
            case 51:return "illager/illusioner";case 53:return "iron_golem/iron_golem";
            case 56:return "item/item_frame";case 58:return "lead_knot";
            case 60:case 103:return "llama/"+variant(LLAMAS,a[21]);
            case 62:return "slime/magmacube";case 65:return "cow/"+(a[17]==1?"brown_mooshroom":"red_mooshroom");
            case 66:return "horse/mule";case 67:return "cat/ocelot";case 68:return "item/painting";
            case 69:return "panda/"+variant(PANDAS,(a[20]==4||a[20]==5)&&a[20]!=a[21]?0:a[20]);case 70:return "parrot/parrot_"+variant(PARROTS,a[19]);
            case 71:return "phantom";case 72:return "pig/pig";case 73:return "piglin/piglin";
            case 74:return "piglin/piglin_brute";case 75:return "illager/pillager";case 76:return "bear/polarbear";
            case 78:return "fish/pufferfish";case 79:return "rabbit/"+(a[17]==99?"caerbannog":variant(RABBITS,a[17]));
            case 80:return "illager/ravager";case 81:return "fish/salmon";case 82:return "sheep/sheep";
            case 83:return "shulker/shulker"+(a[18]>=0&&a[18]<16?"_"+COLORS[a[18]]:"");
            case 85:return "silverfish";case 86:return "skeleton/skeleton";case 87:return "horse/horse_skeleton";
            case 88:return "slime/slime";case 90:return "sniffer/sniffer";case 91:return "snow_golem";
            case 95:return "spider/spider";case 96:return "squid/squid";case 97:return "skeleton/stray";
            case 98:return a[18]!=0?"strider/strider_cold":"strider/strider";case 99:return "tadpole/tadpole";
            case 101:return "block/tnt_side";case 105:return (a[17]&255)==0?"fish/tropical_a":"fish/tropical_b";
            case 106:return "turtle/big_sea_turtle";case 107:return (a[16]&1)!=0?"illager/vex_charging":"illager/vex";case 108:return "villager/villager";
            case 109:return "illager/vindicator";case 110:return "wandering_trader";case 111:return "warden/warden";
            case 112:return "witch";case 113:return "wither/wither";case 114:return "skeleton/wither_skeleton";
            case 116:return a[21]>0?"wolf/wolf_angry":(a[17]&4)!=0?"wolf/wolf_tame":"wolf/wolf";
            case 117:return "hoglin/zoglin";case 119:return "horse/horse_zombie";
            case 120:return "zombie_villager/zombie_villager";case 121:return "piglin/zombified_piglin";
            default: if(com.newhorizon.thinclient.world.RidingState.minecart(e.type))return "minecart";
                return null; // Non-model entities are delegated to their respective block/item/particle renderers.
        }
    }
    static String overlay(EntityTracker.Renderable e,int pass) {
        int[] a=e.appearance;
        if(e.type==108||e.type==120) {
            String base=e.type==108?"villager/":"zombie_villager/";
            if(pass==0)return base+"type/"+variant(VILLAGERS,a[28]);
            int profession=a[29];return profession>0&&profession<PROFESSIONS.length?base+"profession/"+PROFESSIONS[profession]:null;
        }
        if(pass!=0)return null;
        switch(e.type) {
            case 11:return (a[17]&4)!=0?"cat/cat_collar":null;
            case 23:return "zombie/drowned_outer_layer";
            case 49:{int marking=(a[18]>>>8)&255;return marking>0&&marking<HORSE_MARKINGS.length?"horse/horse_markings_"+HORSE_MARKINGS[marking]:null;}
            case 60:return a[20]>=0&&a[20]<16?"llama/decor/"+COLORS[a[20]]:null;
            case 72:return a[17]!=0?"pig/pig_saddle":null;
            case 97:return "skeleton/stray_overlay";
            case 98:return a[19]!=0?"strider/strider_saddle":null;
            case 103:return "llama/decor/trader_llama";
            case 105:return ((a[17]&255)==0?"fish/tropical_a_pattern_":"fish/tropical_b_pattern_")+(Math.max(0,Math.min(5,(a[17]>>>8)&255))+1);
            case 116:return (a[17]&4)!=0?"wolf/wolf_collar":null;
            default:return null;
        }
    }
    static int overlayColor(EntityTracker.Renderable e,int pass) {
        if(pass!=0)return 0xffffff;
        if(e.type==105)return dye((e.appearance[17]>>>24)&255);
        return e.type==11?dye(e.appearance[22]):e.type==116?dye(e.appearance[20]):0xffffff;
    }
    static int dye(int value){return DYES[Math.max(0,Math.min(15,value))];}
    private EntityAppearance() { }
}
