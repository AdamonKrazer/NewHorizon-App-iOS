package com.newhorizon.thinclient.render;

import com.newhorizon.thinclient.audio.BiomeSoundRegistry;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL33;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.ByteBuffer;

/** 128 bounded biome palettes, driven by the server registry and original color maps. */
final class BiomeTintAtlas implements AutoCloseable {
    private final ByteBuffer pixels=BufferUtils.createByteBuffer(128*3*4);
    private int texture;
    private long revision=-1;
    void update(BiomeSoundRegistry registry) {
        if(texture!=0 && revision==registry.revision())return;
        BufferedImage grass=read("grass"),foliage=read("foliage");
        pixels.clear();
        for(int kind=0;kind<3;kind++)for(int id=0;id<128;id++) {
            BiomeSoundRegistry.Biome b=id==127?null:registry.get(id);
            float temp=b==null?.8f:b.temperature,downfall=b==null?.4f:b.downfall;
            int x=(int)((1-clamp(temp))*255),y=(int)((1-clamp(temp)*clamp(downfall))*255);
            int color;
            if(kind==2)color=b==null?0x3f76e4:b.waterColor;
            else if(kind==0) {
                color=b!=null&&b.grassColor>=0?b.grassColor:grass.getRGB(x,y)&0xffffff;
                if(b!=null && "dark_forest".equals(b.grassModifier))color=((color&0xfefefe)+0x28340a)>>1;
                if(b!=null && "swamp".equals(b.grassModifier))color=0x6a7039;
            } else color=b!=null&&b.foliageColor>=0?b.foliageColor:foliage.getRGB(x,y)&0xffffff;
            pixels.put((byte)(color>>16)).put((byte)(color>>8)).put((byte)color).put((byte)255);
        }
        pixels.flip();
        if(texture==0)texture=GL33.glGenTextures();bind();
        GL33.glTexParameteri(GL33.GL_TEXTURE_2D,GL33.GL_TEXTURE_MIN_FILTER,GL33.GL_NEAREST);
        GL33.glTexParameteri(GL33.GL_TEXTURE_2D,GL33.GL_TEXTURE_MAG_FILTER,GL33.GL_NEAREST);
        GL33.glTexImage2D(GL33.GL_TEXTURE_2D,0,GL33.GL_RGBA8,128,3,0,GL33.GL_RGBA,GL33.GL_UNSIGNED_BYTE,pixels);
        revision=registry.revision();
    }
    void bind(){GL33.glActiveTexture(GL33.GL_TEXTURE2);GL33.glBindTexture(GL33.GL_TEXTURE_2D,texture);}
    private static float clamp(float n){return Math.max(0,Math.min(1,n));}
    private static BufferedImage read(String name) {
        try(InputStream in=BiomeTintAtlas.class.getResourceAsStream("/assets/newhorizon/colormap/"+name+".png")) {
            if(in==null)throw new IllegalStateException("Missing vanilla colormap "+name);
            return ImageIO.read(in);
        } catch(java.io.IOException e){throw new IllegalStateException("Vanilla colormap "+name,e);}
    }
    public void close(){if(texture!=0)GL33.glDeleteTextures(texture);texture=0;revision=-1;}
}
