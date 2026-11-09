package com.newhorizon.thinclient.render;

import java.nio.ByteBuffer;
import org.lwjgl.opengl.GL33;

/** Initialization-only, gamma-correct mipmaps confined to each 32px atlas cell. */
final class BlockMipmaps {
    static final int LEVELS = 5;
    private static final float[] POW22 = new float[256];
    static {
        for (int i=0;i<256;i++) POW22[i]=(float)Math.pow(i/255.0f,2.2);
    }
    private BlockMipmaps() { }

    /** Consumes the staging buffer in place; no extra full-atlas allocation. */
    static void upload(int width,int height,ByteBuffer pixels) {
        if(width<32||height<32||width%32!=0||height%32!=0||pixels.remaining()!=width*height*4)
            throw new IllegalArgumentException("Whole 32px RGBA atlas cells required");
        boolean[] transparent=transparentCells(pixels,width,height);
        GL33.glTexParameteri(GL33.GL_TEXTURE_2D,GL33.GL_TEXTURE_MIN_FILTER,GL33.GL_NEAREST_MIPMAP_LINEAR);
        GL33.glTexParameteri(GL33.GL_TEXTURE_2D,GL33.GL_TEXTURE_MAG_FILTER,GL33.GL_NEAREST);
        // A sixth reduction would mix neighboring materials and animation frames.
        GL33.glTexParameteri(GL33.GL_TEXTURE_2D,GL33.GL_TEXTURE_MAX_LEVEL,LEVELS);
        int columns=width/32,cell=32;
        for(int level=0;level<=LEVELS;level++) {
            pixels.position(0);pixels.limit(width*height*4);
            GL33.glTexImage2D(GL33.GL_TEXTURE_2D,level,GL33.GL_RGBA8,width,height,0,
                    GL33.GL_RGBA,GL33.GL_UNSIGNED_BYTE,pixels);
            if(level==LEVELS)break;
            reduce(pixels,width,height,cell,columns,transparent);
            width>>=1;height>>=1;cell>>=1;
        }
    }
    static boolean[] transparentCells(ByteBuffer pixels,int width,int height) {
        int columns=width/32;boolean[] result=new boolean[columns*(height/32)];
        for(int y=0;y<height;y++)for(int x=0;x<width;x++)
            if(pixels.get((y*width+x)*4+3)==0)result[(y/32)*columns+x/32]=true;
        return result;
    }
    static void reduce(ByteBuffer pixels,int width,int height,int cell,int columns,boolean[] transparent) {
        int nextWidth=width/2,nextHeight=height/2,nextCell=cell/2;
        for(int y=0;y<nextHeight;y++)for(int x=0;x<nextWidth;x++) {
            int at=(y*2*width+x*2)*4;
            int a=pixel(pixels,at),b=pixel(pixels,at+4),c=pixel(pixels,at+width*4),d=pixel(pixels,at+width*4+4);
            int color=blend(a,b,c,d,transparent[(y/nextCell)*columns+x/nextCell]);
            int out=(y*nextWidth+x)*4;
            pixels.put(out,(byte)(color>>16));pixels.put(out+1,(byte)(color>>8));
            pixels.put(out+2,(byte)color);pixels.put(out+3,(byte)(color>>24));
        }
    }
    private static int pixel(ByteBuffer p,int at) {
        return (p.get(at+3)&255)<<24|(p.get(at)&255)<<16|(p.get(at+1)&255)<<8|(p.get(at+2)&255);
    }
    static int blend(int a,int b,int c,int d,boolean transparent) {
        // Level 1 of the doubled vanilla 16px sprites must retain every source texel.
        if(a==b&&a==c&&a==d)return transparent&&(a>>>24)==0?0:a;
        if(transparent) {
            if((a>>>24)==0)a=0;if((b>>>24)==0)b=0;if((c>>>24)==0)c=0;if((d>>>24)==0)d=0;
        }
        int alpha=channel(a,b,c,d,24,transparent);
        if(transparent&&alpha<96)alpha=0;
        return alpha<<24|channel(a,b,c,d,16,transparent)<<16
                |channel(a,b,c,d,8,transparent)<<8|channel(a,b,c,d,0,transparent);
    }
    private static int channel(int a,int b,int c,int d,int shift,boolean transparent) {
        float sum=POW22[(a>>>shift)&255]+POW22[(b>>>shift)&255]
                +POW22[(c>>>shift)&255]+POW22[(d>>>shift)&255];
        // Match vanilla's two rounding paths for transparent and opaque sprites.
        double linear=transparent?sum/4.0f:sum*.25;
        double gamma=Math.pow(linear,1.0/2.2);
        if(!transparent)gamma=(float)gamma;
        return (int)(gamma*255.0);
    }
}
