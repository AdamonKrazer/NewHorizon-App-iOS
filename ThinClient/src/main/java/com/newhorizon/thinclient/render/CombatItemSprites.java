package com.newhorizon.thinclient.render;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.InputStream;
/** Two cached weapon textures; never loads the Minecraft item renderer registry. */
public final class CombatItemSprites {
    private static final String[] keys=new String[2];
    private static final int[][] pixels=new int[2][];
    private static int next;
    public static int[] load(String material,String tag) { return VanillaItemSprites.load(material,tag); }
    public static int[] load(String material) { return load(material,null); }
    static int[] legacy(String material) {
        String id=material.replace("minecraft:","");
        if(!id.matches("[a-z0-9_]+"))return null;
        for(int i=0;i<keys.length;i++)if(id.equals(keys[i]))return pixels[i];
        int slot=next;next=(next+1)%2;keys[slot]=id;pixels[slot]=null;
        try(InputStream in=CombatItemSprites.class.getResourceAsStream("/assets/newhorizon/combat_items/"+id+".png")) {
            if(in==null)return null;BufferedImage image=ImageIO.read(in);
            int[] data=new int[256];
            for(int y=0;y<16;y++)for(int x=0;x<16;x++) {
                int argb=image.getRGB(x*image.getWidth()/16,y*image.getHeight()/16);
                data[y*16+x]=(argb>>>24)<128?0:argb&0xffffff;
            }
            return pixels[slot]=data;
        } catch(java.io.IOException error) {return null;}
    }
}
