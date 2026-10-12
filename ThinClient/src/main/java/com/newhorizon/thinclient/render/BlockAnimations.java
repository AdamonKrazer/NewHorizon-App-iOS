package com.newhorizon.thinclient.render;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL33;
import java.io.*;
import java.nio.*;
import java.util.Arrays;

/** Immutable vanilla frames. Only a small uniform table changes at 20 Hz. */
final class BlockAnimations implements AutoCloseable {
    static final String RESOURCE="/assets/newhorizon/block_textures/animation-frames.bin";
    private static BlockAnimations active;
    private final FloatBuffer frames=BufferUtils.createFloatBuffer(128);
    private final int[] programs=new int[32],locations=new int[32];
    private final long[] revisions=new long[32];
    private int count,lookup,texture;
    private int[][] timeline;
    private long start,tick=-1;

    void initializeGl() {
        try(DataInputStream in=new DataInputStream(new BufferedInputStream(BlockAnimations.class.getResourceAsStream(RESOURCE)))) {
            if(in.readInt()!=0x4e48414e||in.readInt()!=1)throw new IOException("Animation atlas format");
            int width=in.readInt(),height=in.readInt(),animations=in.readInt();
            if(width!=2048||height<32||height>2048||height%32!=0||animations<1||animations>128)throw new IOException("Animation atlas bounds");
            timeline=new int[animations][];
            ByteBuffer index=BufferUtils.createByteBuffer(32*64*4);
            for(int i=0;i<animations;i++) {
                int tile=in.readInt(),length=in.readInt();
                if(tile<0||tile>=2048||length<1||length>65536)throw new IOException("Animation timeline bounds");
                index.put(tile*4,(byte)(i+1));timeline[i]=new int[length];
                for(int j=0;j<length;j++) {
                    int frame=in.readUnsignedShort();if(frame>=width*height/1024)throw new IOException("Animation frame bounds");
                    timeline[i][j]=frame;
                }
            }
            ByteBuffer pixels=BufferUtils.createByteBuffer(width*height*4);byte[] row=new byte[width*4];
            for(int y=0;y<height;y++){in.readFully(row);pixels.put(row);}pixels.flip();
            if(in.read()!=-1)throw new IOException("Animation atlas trailing data");
            lookup=create(4,32,64,index);texture=create(5,width,height,pixels);
            start=System.nanoTime();active=this;update(start);
            System.out.println("[NH-TEXTURES] immutableAnimations="+animations+" frameAtlasKiB="+pixels.capacity()/1024+" runtimeTextureUploads=0");
        }catch(IOException|NullPointerException e){throw new IllegalStateException("Vanilla animation atlas",e);}
    }
    private static int create(int unit,int width,int height,ByteBuffer pixels){
        int id=GL33.glGenTextures();GL33.glActiveTexture(GL33.GL_TEXTURE0+unit);GL33.glBindTexture(GL33.GL_TEXTURE_2D,id);
        GL33.glTexParameteri(GL33.GL_TEXTURE_2D,GL33.GL_TEXTURE_MIN_FILTER,GL33.GL_NEAREST);
        GL33.glTexParameteri(GL33.GL_TEXTURE_2D,GL33.GL_TEXTURE_MAG_FILTER,GL33.GL_NEAREST);
        GL33.glTexParameteri(GL33.GL_TEXTURE_2D,GL33.GL_TEXTURE_WRAP_S,GL33.GL_CLAMP_TO_EDGE);
        GL33.glTexParameteri(GL33.GL_TEXTURE_2D,GL33.GL_TEXTURE_WRAP_T,GL33.GL_CLAMP_TO_EDGE);
        GL33.glTexImage2D(GL33.GL_TEXTURE_2D,0,GL33.GL_RGBA8,width,height,0,GL33.GL_RGBA,GL33.GL_UNSIGNED_BYTE,pixels);return id;
    }
    void update(long now){
        long next=Math.max(0,(now-start)/50_000_000L);if(next==tick)return;tick=next;
        frames.clear();for(int[] sequence:timeline)frames.put(sequence[(int)(tick%sequence.length)]);frames.flip();
    }
    static void bind(int program){
        BlockAnimations a=active;if(a==null)return;
        int i=0;while(i<a.count&&a.programs[i]!=program)i++;
        if(i==a.count){
            if(i==a.programs.length)throw new IllegalStateException("Animation shader capacity");
            a.programs[i]=program;a.locations[i]=GL33.glGetUniformLocation(program,"nhAnimationFrames[0]");a.revisions[i]=-1;a.count++;
            if(a.locations[i]>=0){
                GL33.glUniform1i(GL33.glGetUniformLocation(program,"nhAnimationIndex"),4);
                GL33.glUniform1i(GL33.glGetUniformLocation(program,"nhAnimationAtlas"),5);
            }
        }
        if(a.locations[i]<0)return;
        if(a.revisions[i]!=a.tick){GL33.glUniform1fv(a.locations[i],a.frames);a.revisions[i]=a.tick;}
        GL33.glActiveTexture(GL33.GL_TEXTURE4);GL33.glBindTexture(GL33.GL_TEXTURE_2D,a.lookup);
        GL33.glActiveTexture(GL33.GL_TEXTURE5);GL33.glBindTexture(GL33.GL_TEXTURE_2D,a.texture);
        GL33.glActiveTexture(GL33.GL_TEXTURE0);
    }
    static void forget(int program){
        BlockAnimations a=active;if(a==null)return;
        for(int i=0;i<a.count;i++)if(a.programs[i]==program){int last=--a.count;a.programs[i]=a.programs[last];a.locations[i]=a.locations[last];a.revisions[i]=a.revisions[last];break;}
    }
    static String shader(int type,String source){
        if(type==GL33.GL_VERTEX_SHADER){
            String tile=source.contains("flat out int vTexture;")?"vTexture&4095":source.contains("out vec2 vUv;")?"int(floor(vUv.y*64.))*32+int(floor(vUv.x*32.))":null;
            if(tile==null)return source;
            return source.replaceFirst("void\\s+main\\s*\\(\\s*\\)","void nhAtlasMain()")
                    +"\nflat out int nhBlockTile;flat out int nhBlockFrame;uniform float nhAnimationFrames[128];\n"
                    +"#ifdef GL_ES\nuniform highp sampler2D nhAnimationIndex;\n#else\nuniform sampler2D nhAnimationIndex;\n#endif\n"
                    +"void main(){nhAtlasMain();nhBlockTile="+tile+";nhBlockFrame=-1;if(nhBlockTile>=0&&nhBlockTile<2048){"
                    +"int i=int(texelFetch(nhAnimationIndex,ivec2(nhBlockTile%32,nhBlockTile/32),0).r*255.+.5);"
                    +"if(i>0)nhBlockFrame=int(nhAnimationFrames[i-1]+.5);}}\n";
        }
        if(!source.contains("texture(uBlockAtlas,")&&!source.contains("texture(uBlocks,"))return source;
        String header="flat in int nhBlockTile;flat in int nhBlockFrame;\n"
                +"#ifdef GL_ES\nuniform highp sampler2D nhAnimationAtlas;\n#else\nuniform sampler2D nhAnimationAtlas;\n#endif\n"
                +"vec4 nhBlockSample(sampler2D base,vec2 uv){ivec2 p=ivec2(floor(uv*vec2(1024.,2048.)));"
                +"int tile=(p.y/32)*32+p.x/32;if(nhBlockFrame<0||tile!=nhBlockTile)return texture(base,uv);"
                +"return texelFetch(nhAnimationAtlas,ivec2(nhBlockFrame%64,nhBlockFrame/64)*32+(p%32),0);}\n";
        return source.replace("#version 150 core\n","#version 150 core\n"+header)
                .replace("texture(uBlockAtlas,","nhBlockSample(uBlockAtlas,").replace("texture(uBlocks,","nhBlockSample(uBlocks,");
    }
    public void close(){
        if(active==this)active=null;
        if(lookup!=0)GL33.glDeleteTextures(lookup);if(texture!=0)GL33.glDeleteTextures(texture);
        lookup=texture=count=0;tick=-1;timeline=null;Arrays.fill(programs,0);
    }
}
