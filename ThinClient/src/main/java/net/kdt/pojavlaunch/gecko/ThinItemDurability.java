package net.kdt.pojavlaunch.gecko;

import java.util.Locale;

/** Vanilla 1.20.1 item-bar values; visual only, the original stack remains server-owned. */
final class ThinItemDurability {
    private static final java.util.LinkedHashMap<String,ThinItemTag> TAGS=
            new java.util.LinkedHashMap<String,ThinItemTag>(32,.75f,true) {
                protected boolean removeEldestEntry(java.util.Map.Entry<String,ThinItemTag> e){return size()>128;}
            };
    static int maximum(String material) {
        if(material==null)return 0;
        String id=material.toLowerCase(Locale.ROOT);
        if(id.startsWith("minecraft:"))id=id.substring(10);
        if(id.indexOf(':')>=0)return 0;
        switch(id) {
            case "bow":return 384;case "crossbow":return 465;case "trident":return 250;
            case "fishing_rod":return 64;case "flint_and_steel":return 64;case "brush":return 64;
            case "shears":return 238;case "shield":return 336;case "elytra":return 432;
            case "carrot_on_a_stick":return 25;case "warped_fungus_on_a_stick":return 100;
        }
        int at=id.indexOf('_');if(at<0)return 0;
        String tier=id.substring(0,at),part=id.substring(at+1);
        if(part.equals("sword")||part.equals("shovel")||part.equals("pickaxe")||part.equals("axe")||part.equals("hoe")) {
            switch(tier){case "wooden":return 59;case "stone":return 131;case "iron":return 250;
                case "golden":return 32;case "diamond":return 1561;case "netherite":return 2031;default:return 0;}
        }
        int base=part.equals("helmet")?11:part.equals("chestplate")?16:part.equals("leggings")?15:part.equals("boots")?13:0;
        int multiplier=tier.equals("leather")?5:tier.equals("chainmail")||tier.equals("iron")?15:
                tier.equals("golden")?7:tier.equals("diamond")?33:tier.equals("netherite")?37:tier.equals("turtle")?25:0;
        return base*multiplier;
    }
    static float remaining(String material,String nbt) {
        int max=maximum(material);if(max==0||nbt==null||nbt.isEmpty())return -1;
        ThinItemTag tag=TAGS.get(nbt);
        if(tag==null){tag=ThinItemTag.read(nbt);TAGS.put(nbt,tag);}
        if(tag.unbreakable||tag.damage<=0)return -1;
        return Math.max(0,1-tag.damage/(float)max);
    }
    // The unchanged values above are painted by UIKit rather than Android Canvas.
}
