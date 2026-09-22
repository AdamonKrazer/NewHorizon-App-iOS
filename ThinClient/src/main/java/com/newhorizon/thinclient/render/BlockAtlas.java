package com.newhorizon.thinclient.render;

import com.newhorizon.thinclient.world.VanillaBlockTextures;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL33;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.ByteBuffer;

/** Original block PNGs in fixed 32px cells; only animated strips remain decoded. */
final class BlockAtlas implements AutoCloseable {
    static final int WIDTH=1024,HEIGHT=2048;
    private final ByteBuffer tilePixels=BufferUtils.createByteBuffer(32*32*4);
    private final Animation[] animations=new Animation[128];
    private int texture,animationCount;
    private long start,lastTick=-1;

    void initializeGl() {
        int count=VanillaBlockTextures.tileCount();
        if(count>WIDTH/32*(HEIGHT/32))throw new IllegalStateException("Block atlas capacity exceeded: "+count);
        texture=GL33.glGenTextures();bind(1);
        GL33.glTexParameteri(GL33.GL_TEXTURE_2D,GL33.GL_TEXTURE_MIN_FILTER,GL33.GL_NEAREST);
        GL33.glTexParameteri(GL33.GL_TEXTURE_2D,GL33.GL_TEXTURE_MAG_FILTER,GL33.GL_NEAREST);
        GL33.glTexParameteri(GL33.GL_TEXTURE_2D,GL33.GL_TEXTURE_WRAP_S,GL33.GL_CLAMP_TO_EDGE);
        GL33.glTexParameteri(GL33.GL_TEXTURE_2D,GL33.GL_TEXTURE_WRAP_T,GL33.GL_CLAMP_TO_EDGE);
        GL33.glTexImage2D(GL33.GL_TEXTURE_2D,0,GL33.GL_RGBA8,WIDTH,HEIGHT,0,GL33.GL_RGBA,GL33.GL_UNSIGNED_BYTE,0L);
        long retained=0;
        for(int tile=0;tile<count;tile++) {
            BufferedImage png=read(tile);
            Animation a=new Animation(tile,png.getWidth(),png.getHeight(),png.getRGB(0,0,png.getWidth(),png.getHeight(),null,0,png.getWidth()));
            upload(a,VanillaBlockTextures.frameIndex(tile,0),VanillaBlockTextures.frameIndex(tile,0),0);
            if(VanillaBlockTextures.sequenceLength(tile)>1) {
                if(animationCount==animations.length)throw new IllegalStateException("Animated texture capacity exceeded");
                animations[animationCount++]=a;retained+=a.pixels.length*4L;
            }
        }
        start=System.nanoTime();
        System.out.println("[NH-TEXTURES] blocks="+count+" atlasKiB="+(WIDTH*HEIGHT*4/1024)+" animated="+animationCount+" retainedStripKiB="+(retained/1024));
    }
    void update(long now) {
        long tick=Math.max(0,(now-start)/50_000_000L);if(tick==lastTick)return;lastTick=tick;
        bind(1);
        for(int n=0;n<animationCount;n++) {
            Animation a=animations[n];int tile=a.tile,length=VanillaBlockTextures.sequenceLength(tile),cycle=0;
            for(int i=0;i<length;i++)cycle+=VanillaBlockTextures.frameTime(tile,i);
            int local=(int)(tick%Math.max(1,cycle)),index=0;
            while(index<length-1&&local>=VanillaBlockTextures.frameTime(tile,index))local-=VanillaBlockTextures.frameTime(tile,index++);
            int frame=VanillaBlockTextures.frameIndex(tile,index);
            boolean interpolate=VanillaBlockTextures.interpolate(tile);
            if(frame==a.lastFrame&&!interpolate)continue;
            int next=VanillaBlockTextures.frameIndex(tile,(index+1)%length);
            upload(a,frame,next,interpolate?local/(float)VanillaBlockTextures.frameTime(tile,index):0);
            a.lastFrame=frame;
        }
    }
    private void upload(Animation a,int frame,int next,float blend) {
        int fw=VanillaBlockTextures.frameWidth(a.tile),fh=VanillaBlockTextures.frameHeight(a.tile),columns=a.width/fw;
        if(fw<=0||fh<=0||columns==0||frame<0||next<0||frame>=a.width/fw*(a.height/fh)||next>=a.width/fw*(a.height/fh))
            throw new IllegalStateException("Invalid vanilla texture frame "+a.tile);
        tilePixels.clear();
        for(int y=0;y<32;y++)for(int x=0;x<32;x++) {
            int sx=x*fw/32,sy=y*fh/32;
            int c=a.pixels[(frame/columns*fh+sy)*a.width+frame%columns*fw+sx];
            if(blend>0){int d=a.pixels[(next/columns*fh+sy)*a.width+next%columns*fw+sx];
                int r=(int)(((c>>16)&255)*(1-blend)+((d>>16)&255)*blend),g=(int)(((c>>8)&255)*(1-blend)+((d>>8)&255)*blend),b=(int)((c&255)*(1-blend)+(d&255)*blend);
                c=(c&0xff000000)|(r<<16)|(g<<8)|b;}
            tilePixels.put((byte)(c>>16)).put((byte)(c>>8)).put((byte)c).put((byte)(c>>24));
        }
        tilePixels.flip();GL33.glTexSubImage2D(GL33.GL_TEXTURE_2D,0,a.tile%32*32,a.tile/32*32,32,32,GL33.GL_RGBA,GL33.GL_UNSIGNED_BYTE,tilePixels);
    }
    private static BufferedImage read(int tile) {
        try(InputStream in=BlockAtlas.class.getResourceAsStream(VanillaBlockTextures.tileResource(tile))) {
            if(in==null)throw new IllegalStateException("Missing block texture "+tile);
            BufferedImage png=ImageIO.read(in);
            if(png==null||png.getWidth()>512||png.getHeight()>8192)throw new IllegalStateException("Invalid block PNG "+tile);
            return png;
        } catch(java.io.IOException e){throw new IllegalStateException("Block texture "+tile,e);}
    }
    void bind(int unit){GL33.glActiveTexture(GL33.GL_TEXTURE0+unit);GL33.glBindTexture(GL33.GL_TEXTURE_2D,texture);}
    public void close(){if(texture!=0)GL33.glDeleteTextures(texture);texture=0;animationCount=0;java.util.Arrays.fill(animations,null);lastTick=-1;}
    private static final class Animation {
        final int tile,width,height;final int[] pixels;int lastFrame=-1;
        Animation(int tile,int width,int height,int[] pixels){this.tile=tile;this.width=width;this.height=height;this.pixels=pixels;}
    }
}
