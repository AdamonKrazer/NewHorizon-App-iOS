package com.newhorizon.thinclient.render;

import java.nio.ByteBuffer;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL33;

/** Immutable per-corner UV/material lookup; chunk geometry remains 16 bytes per vertex. */
final class BlockModelAtlas implements AutoCloseable {
    static final int WIDTH=1024;
    private int texture;
    void initializeGl() {
        int count=VanillaBlockModels.cornerCount(),height=Math.max(1,(count*BlockModelCodec.TEXELS+WIDTH-1)/WIDTH);
        ByteBuffer data=BufferUtils.createByteBuffer(WIDTH*height*4);
        for(int corner=0;corner<count;corner++)BlockModelCodec.put(data,VanillaBlockModels.descriptor(corner),
                VanillaBlockModels.textureU(corner),VanillaBlockModels.textureV(corner),VanillaBlockModels.shade(corner));
        data.position(0);data.limit(data.capacity());
        texture=GL33.glGenTextures();bind();
        GL33.glTexParameteri(GL33.GL_TEXTURE_2D,GL33.GL_TEXTURE_MIN_FILTER,GL33.GL_NEAREST);
        GL33.glTexParameteri(GL33.GL_TEXTURE_2D,GL33.GL_TEXTURE_MAG_FILTER,GL33.GL_NEAREST);
        GL33.glTexParameteri(GL33.GL_TEXTURE_2D,GL33.GL_TEXTURE_WRAP_S,GL33.GL_CLAMP_TO_EDGE);
        GL33.glTexParameteri(GL33.GL_TEXTURE_2D,GL33.GL_TEXTURE_WRAP_T,GL33.GL_CLAMP_TO_EDGE);
        GL33.glTexImage2D(GL33.GL_TEXTURE_2D,0,GL33.GL_RGBA8,WIDTH,height,0,GL33.GL_RGBA,GL33.GL_UNSIGNED_BYTE,data);
        System.out.println("[NH-MODELS] nibble UV/material GPU table corners="+count+" bytes="+(WIDTH*height*4));
    }
    void bind(){GL33.glActiveTexture(GL33.GL_TEXTURE3);GL33.glBindTexture(GL33.GL_TEXTURE_2D,texture);}
    void bind(int program){bind();GL33.glUniform1i(GL33.glGetUniformLocation(program,"uBlockModels"),3);}
    public void close(){if(texture!=0)GL33.glDeleteTextures(texture);texture=0;}
}