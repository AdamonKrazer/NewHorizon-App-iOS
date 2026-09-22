package com.newhorizon.thinclient.display;

/** World-display input belongs to the equipped laser, not the player's bare hand. */
public final class DisplayInteractionPolicy {
    private DisplayInteractionPolicy() { }
    public static boolean laser(String material) {
        if(material==null)return false;
        String key=material.toLowerCase(java.util.Locale.ROOT);
        return key.equals("webdisplays:laserpointer")||key.equals("webdisplays:laser_pointer")
                ||key.equals("webdisplays_laserpointer")||key.equals("webdisplays_laser_pointer")
                ||key.equals("laserpointer")||key.equals("laser_pointer");
    }
    public static boolean allowed(String main,String off,boolean screenOpen,boolean dead) {
        return !screenOpen&&!dead&&(laser(main)||laser(off));
    }
}
