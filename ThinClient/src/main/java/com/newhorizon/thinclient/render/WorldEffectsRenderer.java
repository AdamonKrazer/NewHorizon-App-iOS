package com.newhorizon.thinclient.render;

import com.newhorizon.thinclient.world.*;
import java.nio.*;
import java.io.*;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL33;

/** Shared vanilla sprites, existing block atlas, one bounded transparent pass. */
final class WorldEffectsRenderer implements AutoCloseable {
    int visibleParticles;
    private static final int STRIDE=10,QUADS=WorldParticles.CAPACITY+128*12;
    private final FloatBuffer vertices=BufferUtils.createFloatBuffer(QUADS*6*STRIDE);
    private final ByteBuffer upload=BufferUtils.createByteBuffer(32*32*4);
    private final WorldParticles.Particle[] particles=new WorldParticles.Particle[WorldParticles.CAPACITY];
    private final int[] items=new int[64];private final long[] used=new long[64];
    private final float[] light=new float[3];
    private int program,vao,vbo,atlas;private long frame;
    private double rx,rz,ux,uy,uz;
    WorldEffectsRenderer(){for(int i=0;i<particles.length;i++)particles[i]=new WorldParticles.Particle();java.util.Arrays.fill(items,-1);}
    void initializeGl() {
        int vs=shader(GL33.GL_VERTEX_SHADER,VERTEX),fs=shader(GL33.GL_FRAGMENT_SHADER,FRAGMENT);
        program=GL33.glCreateProgram();GL33.glAttachShader(program,vs);GL33.glAttachShader(program,fs);
        GL33.glBindAttribLocation(program,0,"aPosition");GL33.glBindAttribLocation(program,1,"aUv");GL33.glBindAttribLocation(program,2,"aColor");GL33.glBindAttribLocation(program,3,"aBlock");GL33.glLinkProgram(program);
        GL33.glDeleteShader(vs);GL33.glDeleteShader(fs);if(GL33.glGetProgrami(program,GL33.GL_LINK_STATUS)==0)throw new IllegalStateException(GL33.glGetProgramInfoLog(program));
        vao=GL33.glGenVertexArrays();vbo=GL33.glGenBuffers();GL33.glBindVertexArray(vao);GL33.glBindBuffer(GL33.GL_ARRAY_BUFFER,vbo);
        GL33.glBufferData(GL33.GL_ARRAY_BUFFER,(long)vertices.capacity()*4,GL33.GL_STREAM_DRAW);
        int[] sizes={3,2,4,1},offsets={0,3,5,9};for(int i=0;i<4;i++){GL33.glEnableVertexAttribArray(i);GL33.glVertexAttribPointer(i,sizes[i],GL33.GL_FLOAT,false,STRIDE*4,offsets[i]*4L);}
        atlas=GL33.glGenTextures();bindAtlas();GL33.glTexParameteri(GL33.GL_TEXTURE_2D,GL33.GL_TEXTURE_MIN_FILTER,GL33.GL_NEAREST);GL33.glTexParameteri(GL33.GL_TEXTURE_2D,GL33.GL_TEXTURE_MAG_FILTER,GL33.GL_NEAREST);
        GL33.glTexParameteri(GL33.GL_TEXTURE_2D,GL33.GL_TEXTURE_WRAP_S,GL33.GL_CLAMP_TO_EDGE);GL33.glTexParameteri(GL33.GL_TEXTURE_2D,GL33.GL_TEXTURE_WRAP_T,GL33.GL_CLAMP_TO_EDGE);
        GL33.glTexImage2D(GL33.GL_TEXTURE_2D,0,GL33.GL_RGBA8,512,512,0,GL33.GL_RGBA,GL33.GL_UNSIGNED_BYTE,0L);
        try {for(int slot=0;slot<148;slot++)try(InputStream in=WorldEffectsRenderer.class.getResourceAsStream("/assets/newhorizon/particles/"+slot+".png")) {
            if(in==null)throw new IOException("Missing particle "+slot);BufferedImage image=ImageIO.read(in);upload.clear();
            for(int y=0;y<32;y++)for(int x=0;x<32;x++)pixel(image.getRGB(x*image.getWidth()/32,y*image.getHeight()/32));upload(slot);
        }}catch(IOException e){throw new IllegalStateException("Vanilla particle atlas",e);}
        GL33.glBindVertexArray(0);System.out.println("[NH-THIN] world effects particles=512 atlasKiB=1024 sharedBlockAtlas=true");
    }
    private void pixel(int p){upload.put((byte)(p>>16)).put((byte)(p>>8)).put((byte)p).put((byte)(p>>>24));}
    private void upload(int slot){upload.flip();GL33.glTexSubImage2D(GL33.GL_TEXTURE_2D,0,slot%16*32,slot/16*32,32,32,GL33.GL_RGBA,GL33.GL_UNSIGNED_BYTE,upload);}
    private void bindAtlas(){GL33.glActiveTexture(GL33.GL_TEXTURE0);GL33.glBindTexture(GL33.GL_TEXTURE_2D,atlas);}
    private int item(int id) {
        int slot=0;long oldest=Long.MAX_VALUE;for(int i=0;i<64;i++){if(items[i]==id){used[i]=frame;return 192+i;}if(used[i]<oldest){oldest=used[i];slot=i;}}
        if(oldest==frame)return -1;String name=DroppedItemRenderer.name(id);if(name==null)return -1;
        int[] pixels=CombatItemSprites.load(name,"");if(pixels==null)return -1;
        upload.clear();for(int y=0;y<32;y++)for(int x=0;x<32;x++){int p=pixels[y/2*16+x/2];pixel(p==0?0:p|0xff000000);}upload(192+slot);items[slot]=id;used[slot]=frame;return 192+slot;
    }
    void draw(EntityTracker tracker,EntityTracker.Renderable[] entities,int count,float x,float y,float z,float yaw,float pitch,float aspect,float projection) {
        vertices.clear();frame++;bindAtlas();long now=System.nanoTime();
        double a=Math.toRadians(yaw),b=Math.toRadians(pitch);rx=-Math.cos(a);rz=-Math.sin(a);ux=-Math.sin(a)*Math.sin(b);uy=Math.cos(b);uz=Math.cos(a)*Math.sin(b);
        int n=tracker.worldParticles(particles,now,x,y,z);visibleParticles=n;
        for(int i=0;i<n;i++) {
            WorldParticles.Particle p=particles[i];int tile,atlasKind=0,color=p.color;float u=p.u,v=p.v,du=.25f,size=p.size,alpha=1;
            boolean debris=p.type==WorldParticles.BLOCK||p.type==WorldParticles.ITEM||p.type==25;
            if(p.type==WorldParticles.BLOCK||p.type==25) {
                int descriptor=VanillaBlockTextures.representative(p.data);tile=VanillaBlockTextures.tile(descriptor);atlasKind=1;
                int tint=VanillaBlockTextures.tint(descriptor);color=tint==1?0x7cbd6b:tint==2?0x48b518:tint==4?0x619961:tint==5?0x80a755:color;
                // Grass terrain fragments use the dirt particle sprite and do not inherit grass tint.
                if(VanillaBlockTextures.name(tile).equals("dirt"))color=0xffffff;
                color=scaleColor(color,.6f,.6f,.6f);
            } else if(p.type==WorldParticles.ITEM){tile=item(p.data);if(tile<0)continue;}
            else {tile=ParticleSprites.frame(p.type,p.age,p.life);u=v=0;du=1;
                if(p.type==5||p.type==44||p.type==48||p.type==51){size*=Math.min(1,p.age/4);color=p.type==48?color:0x777777;}
                if(p.type==0)alpha=.15f;
                if(p.type==65||p.type==66)alpha=Math.min(1,(p.life-p.age)/20);
            }
            boolean emissive=p.type==28||p.type==33||p.type==81||p.type==23||p.type==20||p.type==91;
            if(!emissive){SceneAtmosphere.light((float)p.x,(float)p.y,(float)p.z,light);color=scaleColor(color,light[0],light[1],light[2]);}
            quad(p.x,p.y,p.z,rx*size/2,0,rz*size/2,ux*size/2,uy*size/2,uz*size/2,tile,atlasKind,u,v,u+du,v+du,color,alpha);
        }
        int fire0=VanillaBlockTextures.tileId("fire_0"),fire1=VanillaBlockTextures.tileId("fire_1");
        if(fire0>=0&&fire1>=0)for(int i=0;i<count;i++) {
            EntityTracker.Renderable e=entities[i];if(!e.burning)continue;
            double width=CombatHitboxes.width(e.type)*(e.baby?.5:1)*1.4,height=CombatHitboxes.height(e.type)*(e.baby?.5:1),remaining=height/Math.max(.1,width),offset=0,half=.5;
            double depth=-.3+(int)remaining*.02;
            for(int layer=0;remaining>0&&layer<12;layer++,remaining-=.45,offset+=.45,half*=.9,depth+=.03) {
                boolean flip=(layer/2)%2==0;
                quad(e.x+rz*depth*width,e.y+(offset+.7)*width,e.z-rx*depth*width,rx*half*width,0,rz*half*width,0,.7*width,0,
                        layer%2==0?fire0:fire1,1,flip?1:0,0,flip?0:1,1,0xffffff,1);
            }
        }
        flush(x,y,z,yaw,pitch,aspect,projection,false);
    }
    void overlay(boolean burning,float aspect) {
        if(!burning)return;int tile=VanillaBlockTextures.tileId("fire_1");if(tile<0)return;
        vertices.clear();for(int side=-1;side<=1;side+=2){double a=Math.toRadians(side*10);quad(side*.45,-.7,0,Math.cos(a)*.5,Math.sin(a)*.5,0,-Math.sin(a)*.65,Math.cos(a)*.65,0,tile,1,0,0,1,1,0xffffff,.9f);}
        flush(0,0,0,0,0,aspect,1,true);
    }
    private void flush(float x,float y,float z,float yaw,float pitch,float aspect,float projection,boolean overlay) {
        int count=vertices.position()/STRIDE;if(count==0)return;vertices.flip();ScreenEffects.bind(program);bindAtlas();
        GL33.glUniform3f(GL33.glGetUniformLocation(program,"uCamera"),x,y,z);GL33.glUniform2f(GL33.glGetUniformLocation(program,"uRotation"),yaw,pitch);
        GL33.glUniform1f(GL33.glGetUniformLocation(program,"uAspect"),aspect);GL33.glUniform1f(GL33.glGetUniformLocation(program,"uProjection"),projection);
        GL33.glUniform1i(GL33.glGetUniformLocation(program,"uAtlas"),0);GL33.glUniform1i(GL33.glGetUniformLocation(program,"uBlocks"),1);GL33.glUniform1i(GL33.glGetUniformLocation(program,"uOverlay"),overlay?1:0);
        GL33.glBindVertexArray(vao);GL33.glBindBuffer(GL33.GL_ARRAY_BUFFER,vbo);GL33.glBufferSubData(GL33.GL_ARRAY_BUFFER,0,vertices);
        boolean cull=GL33.glIsEnabled(GL33.GL_CULL_FACE),blend=GL33.glIsEnabled(GL33.GL_BLEND),depth=GL33.glIsEnabled(GL33.GL_DEPTH_TEST),mask=GL33.glGetBoolean(GL33.GL_DEPTH_WRITEMASK);
        int srcRgb=GL33.glGetInteger(GL33.GL_BLEND_SRC_RGB),dstRgb=GL33.glGetInteger(GL33.GL_BLEND_DST_RGB),srcAlpha=GL33.glGetInteger(GL33.GL_BLEND_SRC_ALPHA),dstAlpha=GL33.glGetInteger(GL33.GL_BLEND_DST_ALPHA);
        GL33.glDisable(GL33.GL_CULL_FACE);GL33.glEnable(GL33.GL_BLEND);GL33.glBlendFunc(GL33.GL_SRC_ALPHA,GL33.GL_ONE_MINUS_SRC_ALPHA);GL33.glDepthMask(false);if(overlay)GL33.glDisable(GL33.GL_DEPTH_TEST);
        GL33.glDrawArrays(GL33.GL_TRIANGLES,0,count);GL33.glDepthMask(mask);GL33.glBlendFuncSeparate(srcRgb,dstRgb,srcAlpha,dstAlpha);
        if(cull)GL33.glEnable(GL33.GL_CULL_FACE);if(!blend)GL33.glDisable(GL33.GL_BLEND);if(depth)GL33.glEnable(GL33.GL_DEPTH_TEST);
    }
    private void quad(double x,double y,double z,double ax,double ay,double az,double bx,double by,double bz,int tile,int kind,float u0,float v0,float u1,float v1,int color,float alpha) {
        if(vertices.remaining()<6*STRIDE)return;
        vertex(x-ax-bx,y-ay-by,z-az-bz,tile,kind,u0,v1,color,alpha);vertex(x+ax-bx,y+ay-by,z+az-bz,tile,kind,u1,v1,color,alpha);vertex(x+ax+bx,y+ay+by,z+az+bz,tile,kind,u1,v0,color,alpha);
        vertex(x-ax-bx,y-ay-by,z-az-bz,tile,kind,u0,v1,color,alpha);vertex(x+ax+bx,y+ay+by,z+az+bz,tile,kind,u1,v0,color,alpha);vertex(x-ax+bx,y-ay+by,z-az+bz,tile,kind,u0,v0,color,alpha);
    }
    private void vertex(double x,double y,double z,int tile,int kind,float u,float v,int color,float alpha) {
        float au=kind==1?(tile%32*32+.01f+u*31.98f)/BlockAtlas.WIDTH:(tile%16*32+.01f+u*31.98f)/512;
        float av=kind==1?(tile/32*32+.01f+v*31.98f)/BlockAtlas.HEIGHT:(tile/16*32+.01f+v*31.98f)/512;
        vertices.put((float)x).put((float)y).put((float)z).put(au).put(av).put((color>>16&255)/255f).put((color>>8&255)/255f).put((color&255)/255f).put(alpha).put(kind);
    }
    private static int scaleColor(int c,float r,float g,float b){return (Math.min(255,Math.round((c>>16&255)*r))<<16)|(Math.min(255,Math.round((c>>8&255)*g))<<8)|Math.min(255,Math.round((c&255)*b));}
    private static int shader(int type,String source){int s=GL33.glCreateShader(type);GL33.glShaderSource(s,ScreenEffects.shader(type,source));GL33.glCompileShader(s);if(GL33.glGetShaderi(s,GL33.GL_COMPILE_STATUS)==0)throw new IllegalStateException(GL33.glGetShaderInfoLog(s));return s;}
    public void close(){if(program!=0)ScreenEffects.deleteProgram(program);if(vao!=0)GL33.glDeleteVertexArrays(vao);if(vbo!=0)GL33.glDeleteBuffers(vbo);if(atlas!=0)GL33.glDeleteTextures(atlas);program=vao=vbo=atlas=0;}
    private static final String VERTEX="#version 150 core\n"
        +"in vec3 aPosition;in vec2 aUv;in vec4 aColor;in float aBlock;out vec2 vUv;out vec4 vColor;out vec3 vWorld;flat out float vBlock;uniform vec3 uCamera;uniform vec2 uRotation;uniform float uAspect,uProjection;uniform int uOverlay;"
        +"void main(){vec3 p=aPosition-uCamera;float y=radians(-uRotation.x);p=vec3(cos(y)*p.x-sin(y)*p.z,p.y,sin(y)*p.x+cos(y)*p.z);p.x=-p.x;p.z=-p.z;float x=radians(uRotation.y);p=vec3(p.x,cos(x)*p.y-sin(x)*p.z,sin(x)*p.y+cos(x)*p.z);float n=.05,f=192.;gl_Position=uOverlay!=0?vec4(aPosition,1):vec4(p.x*uProjection/uAspect,p.y*uProjection,-(f+n)/(f-n)*p.z-2.*f*n/(f-n),-p.z);vUv=aUv;vColor=aColor;vWorld=aPosition;vBlock=aBlock;}";
    private static final String FRAGMENT="#version 150 core\n"
        +"in vec2 vUv;in vec4 vColor;in vec3 vWorld;flat in float vBlock;uniform sampler2D uAtlas,uBlocks;uniform vec3 uCamera;uniform int uOverlay;out vec4 fragColor;void main(){vec4 c=(vBlock>.5?texture(uBlocks,vUv):texture(uAtlas,vUv))*vColor;if(c.a<.01)discard;fragColor=vec4(uOverlay!=0?c.rgb:nhFog(c.rgb,vWorld,uCamera),c.a);}";
}
