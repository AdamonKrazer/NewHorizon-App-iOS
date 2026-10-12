package com.newhorizon.thinclient.render;

/** Vanilla equipment slot and material selection; held armor remains a held item. */
final class WornEquipment {
    static String armor(String item,int slot){
        if(item==null)return null;
        String suffix=slot==2?"_boots":slot==3?"_leggings":slot==4?"_chestplate":slot==5?"_helmet":"";
        if(suffix.isEmpty()||!item.endsWith(suffix))return null;
        String material=item.substring(0,item.length()-suffix.length());
        if(material.equals("golden"))material="gold";
        switch(material){case "leather":case "chainmail":case "iron":case "gold":case "diamond":case "netherite":case "turtle":
            return "armor/"+material+"_layer_"+(slot==3?2:1);default:return null;}
    }
    static boolean part(int slot,String path){
        switch(slot){case 2:return path.endsWith("_leg");
            case 3:return path.equals("/body")||path.endsWith("_leg");
            case 4:return path.equals("/body")||path.endsWith("_arm");
            case 5:return path.equals("/head");default:return false;}
    }
    static boolean humanoid(int type,boolean player){
        if(player)return true;
        switch(type){case 2:case 23:case 42:case 50:case 73:case 74:case 86:case 97:case 114:case 118:case 120:case 121:case 122:return true;default:return false;}
    }
    private WornEquipment(){}
}
