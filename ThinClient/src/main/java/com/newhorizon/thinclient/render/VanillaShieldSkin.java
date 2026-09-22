package com.newhorizon.thinclient.render;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import javax.imageio.ImageIO;
/** One retained 64x64 original shield texture, loaded on first equip. */
final class VanillaShieldSkin {
    private static final int[] PIXELS=read();
    static int pixel(int x,int y){return x<0||y<0||x>=64||y>=64?0:PIXELS[y*64+x];}
    private static int[] read(){
        try(InputStream in=VanillaShieldSkin.class.getResourceAsStream("/assets/minecraft/textures/entity/shield_base_nopattern.png")){
            if(in==null)throw new IllegalStateException("Missing original shield texture");
            BufferedImage image=ImageIO.read(in);
            if(image==null||image.getWidth()!=64||image.getHeight()!=64)throw new IllegalStateException("Invalid shield texture");
            return image.getRGB(0,0,64,64,null,0,64);
        }catch(java.io.IOException e){throw new IllegalStateException("Original shield texture",e);}
    }
}
