package com.newhorizon.thinclient.render;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL33;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.ByteBuffer;

/** Eight unmodified vanilla textures; 8 KiB GPU storage. */
final class RailAtlas implements AutoCloseable {
    private int texture;
    void initializeGl() {
        ByteBuffer pixels=BufferUtils.createByteBuffer(64*32*4);
        int[] argb=new int[64*32];
        try {
            for(int tile=0;tile<8;tile++) {
                try(InputStream in=RailAtlas.class.getResourceAsStream("/assets/newhorizon/rails/"+tile+".png")) {
                    if(in==null)throw new IllegalStateException("Missing rail texture "+tile);
                    BufferedImage image=ImageIO.read(in);
                    for(int y=0;y<16;y++)for(int x=0;x<16;x++)argb[(tile/4*16+y)*64+tile%4*16+x]=image.getRGB(x,y);
                }
            }
        } catch(java.io.IOException e) { throw new IllegalStateException("Rail atlas",e); }
        for(int c:argb)pixels.put((byte)(c>>16)).put((byte)(c>>8)).put((byte)c).put((byte)(c>>24));
        pixels.flip();texture=GL33.glGenTextures();bind();
        GL33.glTexParameteri(GL33.GL_TEXTURE_2D,GL33.GL_TEXTURE_MIN_FILTER,GL33.GL_NEAREST);
        GL33.glTexParameteri(GL33.GL_TEXTURE_2D,GL33.GL_TEXTURE_MAG_FILTER,GL33.GL_NEAREST);
        GL33.glTexParameteri(GL33.GL_TEXTURE_2D,GL33.GL_TEXTURE_WRAP_S,GL33.GL_CLAMP_TO_EDGE);
        GL33.glTexParameteri(GL33.GL_TEXTURE_2D,GL33.GL_TEXTURE_WRAP_T,GL33.GL_CLAMP_TO_EDGE);
        GL33.glTexImage2D(GL33.GL_TEXTURE_2D,0,GL33.GL_RGBA,64,32,0,GL33.GL_RGBA,GL33.GL_UNSIGNED_BYTE,pixels);
    }
    void bind() { GL33.glActiveTexture(GL33.GL_TEXTURE0);GL33.glBindTexture(GL33.GL_TEXTURE_2D,texture); }
    public void close() { if(texture!=0)GL33.glDeleteTextures(texture);texture=0; }
}
