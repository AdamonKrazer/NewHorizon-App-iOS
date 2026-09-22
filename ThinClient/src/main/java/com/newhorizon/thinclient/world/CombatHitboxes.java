package com.newhorizon.thinclient.world;
import java.io.*;
import java.nio.charset.StandardCharsets;
/** Default dimensions extracted from the official registry, not render cuboid guesses. */
public final class CombatHitboxes {
    private static final float[] WIDTH=new float[256],HEIGHT=new float[256];
    private static final boolean[] PICK=new boolean[256],KNOWN=new boolean[256],BABY=new boolean[256];
    static {
        try(BufferedReader reader=new BufferedReader(new InputStreamReader(CombatHitboxes.class.getResourceAsStream("/assets/newhorizon/entity_dimensions_1_20_1.tsv"),StandardCharsets.UTF_8))) {
            String row;while((row=reader.readLine())!=null) {
                if(row.startsWith("#"))continue;String[] f=row.split("\t");int id=Integer.parseInt(f[0]);
                if(id<0||id>=256)continue;WIDTH[id]=Float.parseFloat(f[1]);HEIGHT[id]=Float.parseFloat(f[2]);PICK[id]="1".equals(f[3]);KNOWN[id]=true;
                BABY[id]=java.util.Arrays.asList("axolotl","bee","camel","cat","chicken","cow","donkey","fox","frog","goat","hoglin","horse","llama","mooshroom","mule","ocelot","panda","pig","polar_bear","rabbit","sheep","skeleton_horse","sniffer","strider","trader_llama","turtle","villager","wolf","zombie_horse","zombie","zombie_villager","zombified_piglin","drowned","husk","zoglin").contains(f[4]);
            }
        } catch(Exception error) {throw new ExceptionInInitializerError(error);}
    }
    public static double width(int id) {return id>=0&&id<256&&KNOWN[id]?WIDTH[id]:.6;}
    public static double height(int id) {return id>=0&&id<256&&KNOWN[id]?HEIGHT[id]:1.8;}
    public static boolean pickable(int id) {if(id==58)return true;return id>=0&&id<256&&KNOWN[id]?PICK[id]:true;}
    public static double reach(boolean creative) {return creative?6:3;}
    public static boolean hasBabySize(int id) {return id>=0&&id<256&&BABY[id];}
}
