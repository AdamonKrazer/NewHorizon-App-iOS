package com.newhorizon.thinclient.render;

import com.newhorizon.thinclient.world.VanillaBlockTextures;

/** A generated item model takes precedence over the block placed by that item. */
final class HeldItemTextureRoute {
    static final int NONE=0,GENERATED=1,BLOCK=2,SHIELD=3;
    private HeldItemTextureRoute() { }
    static int select(String material) {
        if(material==null)return NONE;
        String key=material.toLowerCase(java.util.Locale.ROOT);
        if(key.startsWith("minecraft:"))key=key.substring(10);
        if(!key.matches("[a-z0-9_]+"))return NONE;
        if(key.equals("shield"))return SHIELD;
        if(VanillaItemSprites.transform(key)!=null)return GENERATED;
        return VanillaBlockTextures.defaultState(key)>=0?BLOCK:NONE;
    }
}
