package com.newhorizon.thinclient.render;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL33;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.ByteBuffer;

/** One fixed atlas, decoded once. Original pixels retain their native resolution. */
final class EntitySkinAtlas implements AutoCloseable {
    static final int HEIGHT=EntitySkins.HEIGHT+5*66;
    private final long[] revisions=new long[com.newhorizon.thinclient.world.PlayerSkins.CAPACITY];
    private final ByteBuffer upload=BufferUtils.createByteBuffer(66*66*4);
    private int texture;
    void initializeGl() {
        ByteBuffer pixels=BufferUtils.createByteBuffer(EntitySkins.WIDTH*HEIGHT*4);
        try {
            for(int i=0;i<EntitySkins.NAMES.length;i++) {
                int at=i*4,x=EntitySkins.RECTS[at],y=EntitySkins.RECTS[at+1],w=EntitySkins.RECTS[at+2],h=EntitySkins.RECTS[at+3];
                try(InputStream in=EntitySkinAtlas.class.getResourceAsStream("/assets/newhorizon/entities/"+EntitySkins.NAMES[i]+".png")) {
                    if(in==null)throw new IllegalStateException("Missing entity skin "+EntitySkins.NAMES[i]);
                    BufferedImage image=ImageIO.read(in);
                    if(image==null||image.getWidth()!=w||image.getHeight()!=h)throw new IllegalStateException("Invalid entity skin "+EntitySkins.NAMES[i]);
                    // One pixel of edge padding prevents neighboring skins leaking at boundaries.
                    for(int py=-1;py<=h;py++)for(int px=-1;px<=w;px++) {
                        int c=image.getRGB(Math.max(0,Math.min(w-1,px)),Math.max(0,Math.min(h-1,py)));
                        int offset=((y+py)*EntitySkins.WIDTH+x+px)*4;
                        pixels.put(offset,(byte)(c>>16));pixels.put(offset+1,(byte)(c>>8));pixels.put(offset+2,(byte)c);pixels.put(offset+3,(byte)(c>>24));
                    }
                }
            }
        } catch(java.io.IOException e) { throw new IllegalStateException("Entity skins",e); }
        texture=GL33.glGenTextures();bind();
        GL33.glTexParameteri(GL33.GL_TEXTURE_2D,GL33.GL_TEXTURE_MIN_FILTER,GL33.GL_NEAREST);
        GL33.glTexParameteri(GL33.GL_TEXTURE_2D,GL33.GL_TEXTURE_MAG_FILTER,GL33.GL_NEAREST);
        GL33.glTexParameteri(GL33.GL_TEXTURE_2D,GL33.GL_TEXTURE_WRAP_S,GL33.GL_CLAMP_TO_EDGE);
        GL33.glTexParameteri(GL33.GL_TEXTURE_2D,GL33.GL_TEXTURE_WRAP_T,GL33.GL_CLAMP_TO_EDGE);
        GL33.glTexImage2D(GL33.GL_TEXTURE_2D,0,GL33.GL_RGBA,EntitySkins.WIDTH,HEIGHT,0,GL33.GL_RGBA,GL33.GL_UNSIGNED_BYTE,pixels);
    }
    int player(com.newhorizon.thinclient.world.PlayerSkins.Slot slot){
        int index=slot.index,x=(index%15)*66,y=EntitySkins.HEIGHT+(index/15)*66;
        long revision=slot.revision;
        if(revisions[index]!=revision){upload.clear();int[] p=slot.skin.pixels;
            for(int v=-1;v<=64;v++)for(int u=-1;u<=64;u++){int c=p[Math.max(0,Math.min(63,v))*64+Math.max(0,Math.min(63,u))];upload.put((byte)(c>>16)).put((byte)(c>>8)).put((byte)c).put((byte)(c>>24));}
            upload.flip();bind();GL33.glTexSubImage2D(GL33.GL_TEXTURE_2D,0,x,y,66,66,GL33.GL_RGBA,GL33.GL_UNSIGNED_BYTE,upload);revisions[index]=revision;
        }
        return (y+1)*EntitySkins.WIDTH+x+1;
    }
    void bind() {GL33.glActiveTexture(GL33.GL_TEXTURE0);GL33.glBindTexture(GL33.GL_TEXTURE_2D,texture);}
    public void close() {if(texture!=0)GL33.glDeleteTextures(texture);texture=0;}
}
