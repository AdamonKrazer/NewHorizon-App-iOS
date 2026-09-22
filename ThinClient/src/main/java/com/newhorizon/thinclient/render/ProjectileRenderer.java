package com.newhorizon.thinclient.render;

import com.newhorizon.thinclient.world.EntityTracker;
import com.newhorizon.thinclient.world.ProjectileKind;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL33;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

/** One 256x256 atlas and one fixed vertex buffer for all vanilla projectiles. */
final class ProjectileRenderer implements AutoCloseable {
    int visibleParticles;
    private static final int FLOATS=9, MAX_QUADS=128*6+96+2048+192;
    private final FloatBuffer vertices=BufferUtils.createFloatBuffer(MAX_QUADS*6*FLOATS);
    private int program,vao,vbo,atlas;
    private final com.newhorizon.thinclient.world.CombatParticles.Particle[] particles=new com.newhorizon.thinclient.world.CombatParticles.Particle[96];
    ProjectileRenderer() { for(int i=0;i<particles.length;i++)particles[i]=new com.newhorizon.thinclient.world.CombatParticles.Particle(); }
    private final com.newhorizon.thinclient.world.FireworkEffects.Particle[] sparks=new com.newhorizon.thinclient.world.FireworkEffects.Particle[2048];
    { for(int i=0;i<sparks.length;i++)sparks[i]=new com.newhorizon.thinclient.world.FireworkEffects.Particle(); }
    private final com.newhorizon.thinclient.world.FishingParticles.Particle[] water=new com.newhorizon.thinclient.world.FishingParticles.Particle[192];
    {for(int i=0;i<water.length;i++)water[i]=new com.newhorizon.thinclient.world.FishingParticles.Particle();}
    private float vertexAlpha=1;
    private double rightX,rightZ,upX,upY,upZ;

    void initializeGl() {
        int vertex=shader(GL33.GL_VERTEX_SHADER,VERTEX), fragment=shader(GL33.GL_FRAGMENT_SHADER,FRAGMENT);
        program=GL33.glCreateProgram(); GL33.glAttachShader(program,vertex); GL33.glAttachShader(program,fragment);
        GL33.glBindAttribLocation(program,0,"aPosition"); GL33.glBindAttribLocation(program,1,"aUv");
        GL33.glBindAttribLocation(program,2,"aColor"); GL33.glLinkProgram(program);
        GL33.glDeleteShader(vertex); GL33.glDeleteShader(fragment);
        if(GL33.glGetProgrami(program,GL33.GL_LINK_STATUS)==0) throw new IllegalStateException(GL33.glGetProgramInfoLog(program));
        vao=GL33.glGenVertexArrays(); vbo=GL33.glGenBuffers(); GL33.glBindVertexArray(vao);
        GL33.glBindBuffer(GL33.GL_ARRAY_BUFFER,vbo);
        GL33.glBufferData(GL33.GL_ARRAY_BUFFER,(long)vertices.capacity()*4,GL33.GL_STREAM_DRAW);
        for(int i=0;i<3;i++) {
            GL33.glEnableVertexAttribArray(i);
            GL33.glVertexAttribPointer(i,i==0?3:i==1?2:4,GL33.GL_FLOAT,false,FLOATS*4,(i==0?0:i==1?3:5)*4L);
        }
        ByteBuffer pixels=BufferUtils.createByteBuffer(256*256*4);
        int[] argb=new int[256*256];
        try {
            for(int tile=0;tile<48;tile++) {
                if(tile==15)continue;
                try(InputStream input=ProjectileRenderer.class.getResourceAsStream("/assets/newhorizon/projectiles/"+tile+".png")) {
                    if(input==null) throw new IllegalStateException("Missing projectile texture "+tile);
                    BufferedImage image=ImageIO.read(input);
                    for(int y=0;y<32;y++) for(int x=0;x<32;x++)
                        argb[(tile/8*32+y)*256+tile%8*32+x]=image.getRGB(x*image.getWidth()/32,y*image.getHeight()/32);
                }
            }
        } catch(java.io.IOException error) { throw new IllegalStateException("Projectile atlas",error); }
        for(int y=32;y<64;y++) for(int x=224;x<256;x++) argb[y*256+x]=0xffffffff;
        for(int value:argb) pixels.put((byte)(value>>16)).put((byte)(value>>8)).put((byte)value).put((byte)(value>>24));
        pixels.flip(); atlas=GL33.glGenTextures(); GL33.glBindTexture(GL33.GL_TEXTURE_2D,atlas);
        GL33.glTexParameteri(GL33.GL_TEXTURE_2D,GL33.GL_TEXTURE_MIN_FILTER,GL33.GL_NEAREST);
        GL33.glTexParameteri(GL33.GL_TEXTURE_2D,GL33.GL_TEXTURE_MAG_FILTER,GL33.GL_NEAREST);
        GL33.glTexParameteri(GL33.GL_TEXTURE_2D,GL33.GL_TEXTURE_WRAP_S,GL33.GL_CLAMP_TO_EDGE);
        GL33.glTexParameteri(GL33.GL_TEXTURE_2D,GL33.GL_TEXTURE_WRAP_T,GL33.GL_CLAMP_TO_EDGE);
        GL33.glTexImage2D(GL33.GL_TEXTURE_2D,0,GL33.GL_RGBA,256,256,0,GL33.GL_RGBA,GL33.GL_UNSIGNED_BYTE,pixels);
        GL33.glBindVertexArray(0);
        System.out.println("[NH-THIN] projectile renderer: vanilla atlas=256KiB, maxQuads="+MAX_QUADS);
    }

    void draw(EntityTracker.Renderable[] entities,int count,float x,float y,float z,
              float yaw,float pitch,float aspect,float projection,float medium,com.newhorizon.thinclient.world.CombatParticles combat,com.newhorizon.thinclient.world.FireworkEffects fireworks,EntityTracker tracker) {
        vertices.clear(); double a=Math.toRadians(yaw),b=Math.toRadians(pitch);
        rightX=-Math.cos(a); rightZ=-Math.sin(a);
        upX=-Math.sin(a)*Math.sin(b); upY=Math.cos(b); upZ=Math.cos(a)*Math.sin(b);
        for(int i=0;i<count;i++) if(ProjectileKind.isProjectile(entities[i].type)||entities[i].type==58) model(entities[i]);
        long now=System.nanoTime();
        int particleCount=combat.snapshot(particles,now);
        for(int i=0;i<particleCount;i++) {
            com.newhorizon.thinclient.world.CombatParticles.Particle p=particles[i];
            int tile=p.type==2?16+Math.min(7,(int)((now-p.born)/50_000_000L)):p.type==1?14:13;
            float size=p.size;
            quad(p.x,p.y,p.z,rightX*size/2,0,rightZ*size/2,upX*size/2,upY*size/2,upZ*size/2,tile,0,0,1,1,p.type==0?0xb3a080:0xffffff);
        }
        int waterCount=tracker.fishingParticles(water,now);
        for(int i=0;i<waterCount;i++) {
            com.newhorizon.thinclient.world.FishingParticles.Particle p=water[i];
            quad(p.x,p.y,p.z,rightX*p.size/2,0,rightZ*p.size/2,upX*p.size/2,upY*p.size/2,upZ*p.size/2,p.tile,0,0,1,1,0xb4d9ff);
        }
        int solidCount=vertices.position()/FLOATS;
        int sparkCount=fireworks.snapshot(sparks,now,x,y,z);
        visibleParticles=particleCount+waterCount+sparkCount;
        for(int i=0;i<sparkCount;i++) {
            com.newhorizon.thinclient.world.FireworkEffects.Particle p=sparks[i];vertexAlpha=p.alpha;
            quad(p.x,p.y,p.z,rightX*p.size/2,0,rightZ*p.size/2,upX*p.size/2,upY*p.size/2,upZ*p.size/2,p.tile,0,0,1,1,p.color);
        }
        vertexAlpha=1;
        if(vertices.position()==0) return;
        int vertexCount=vertices.position()/FLOATS; vertices.flip();
        ScreenEffects.bind(program);
        GL33.glUniform3f(GL33.glGetUniformLocation(program,"uCamera"),x,y,z);
        GL33.glUniform2f(GL33.glGetUniformLocation(program,"uRotation"),yaw,pitch);
        GL33.glUniform1f(GL33.glGetUniformLocation(program,"uAspect"),aspect);
        GL33.glUniform1f(GL33.glGetUniformLocation(program,"uProjection"),projection);
        GL33.glUniform1f(GL33.glGetUniformLocation(program,"uMedium"),medium);
        GL33.glUniform1i(GL33.glGetUniformLocation(program,"uAtlas"),0);
        GL33.glActiveTexture(GL33.GL_TEXTURE0); GL33.glBindTexture(GL33.GL_TEXTURE_2D,atlas);
        GL33.glBindVertexArray(vao); GL33.glBindBuffer(GL33.GL_ARRAY_BUFFER,vbo);
        GL33.glBufferSubData(GL33.GL_ARRAY_BUFFER,0,vertices);
        boolean cull=GL33.glIsEnabled(GL33.GL_CULL_FACE); GL33.glDisable(GL33.GL_CULL_FACE);
        GL33.glDrawArrays(GL33.GL_TRIANGLES,0,solidCount);
        if(vertexCount>solidCount) {
            boolean blend=GL33.glIsEnabled(GL33.GL_BLEND),depthMask=GL33.glGetBoolean(GL33.GL_DEPTH_WRITEMASK);
            GL33.glEnable(GL33.GL_BLEND);GL33.glBlendFunc(GL33.GL_SRC_ALPHA,GL33.GL_ONE_MINUS_SRC_ALPHA);GL33.glDepthMask(false);
            GL33.glDrawArrays(GL33.GL_TRIANGLES,solidCount,vertexCount-solidCount);
            GL33.glDepthMask(depthMask);if(!blend)GL33.glDisable(GL33.GL_BLEND);
        }
        if(cull) GL33.glEnable(GL33.GL_CULL_FACE);
    }

    private void model(EntityTracker.Renderable e) {
        if(e.type==58) {knot(e);return;}
        if(ProjectileKind.arrow(e.type)) { arrow(e); return; }
        int tile; float size=.5f; int color=0xffffff;
        switch(e.type) {
            case ProjectileKind.FISHING: tile=41; break;
            case ProjectileKind.EGG: tile=0; break;
            case ProjectileKind.SNOWBALL: tile=1; break;
            case ProjectileKind.PEARL: tile=2; break;
            case ProjectileKind.EXPERIENCE: tile=3; break;
            case ProjectileKind.POTION: tile=e.itemId==1115?5:4; break;
            case ProjectileKind.FIREBALL: tile=6; size=1; break;
            case ProjectileKind.SMALL_FIREBALL: tile=6; size=.5f; break;
            case ProjectileKind.DRAGON_FIREBALL: tile=7; size=1; break;
            case ProjectileKind.SHULKER: tile=8; size=.3125f; break;
            case ProjectileKind.FIREWORK:
                double ry=Math.toRadians(e.yaw),rp=Math.toRadians(e.pitch);
                quad(e.x,e.y+.125,e.z,Math.cos(ry)*.25,0,-Math.sin(ry)*.25,
                        Math.sin(ry)*Math.cos(rp)*.25,Math.sin(rp)*.25,Math.cos(ry)*Math.cos(rp)*.25,9,0,0,1,1,color);
                return;
            case ProjectileKind.WITHER_SKULL: tile=15; size=.3125f; color=0x343434; break;
            default: tile=15; size=.125f; color=0xe7ded2;
        }
        billboard(e,tile,size,color,0);
        if(e.type==ProjectileKind.POTION) billboard(e,12,size,e.color<0?0x385dc6:e.color,.001);
    }
    private void knot(EntityTracker.Renderable e) {
        double x=e.x,y=e.y+.25,z=e.z,w=.1875,h=.25;
        quad(x,y,z-w,w,0,0,0,h,0,42,6/32f,6/32f,12/32f,14/32f,0xffffff);
        quad(x,y,z+w,w,0,0,0,h,0,42,18/32f,6/32f,24/32f,14/32f,0xffffff);
        quad(x-w,y,z,0,0,w,0,h,0,42,0,6/32f,6/32f,14/32f,0xffffff);
        quad(x+w,y,z,0,0,w,0,h,0,42,12/32f,6/32f,18/32f,14/32f,0xffffff);
        quad(x,y+h,z,w,0,0,0,0,w,42,6/32f,0,12/32f,6/32f,0xffffff);
        quad(x,y-h,z,w,0,0,0,0,w,42,12/32f,0,18/32f,6/32f,0xffffff);
    }
    private void billboard(EntityTracker.Renderable e,int tile,float size,int color,double layer) {
        // Layer offset toward camera uses cross(right, up).
        double nx=-rightZ*upY,ny=rightZ*upX-rightX*upZ,nz=rightX*upY;
        quad(e.x+nx*layer,e.y+size*.25+ny*layer,e.z+nz*layer,
                rightX*size/2,0,rightZ*size/2,upX*size/2,upY*size/2,upZ*size/2,tile,0,0,1,1,color);
    }
    private void arrow(EntityTracker.Renderable e) {
        double yaw=Math.toRadians(e.yaw),pitch=Math.toRadians(e.pitch);
        double fx=Math.sin(yaw)*Math.cos(pitch),fy=Math.sin(pitch),fz=Math.cos(yaw)*Math.cos(pitch);
        double rx=Math.cos(yaw),rz=-Math.sin(yaw);
        double ux=-Math.sin(yaw)*Math.sin(pitch),uy=Math.cos(pitch),uz=-Math.cos(yaw)*Math.sin(pitch);
        if(e.type==ProjectileKind.TRIDENT) {
            quad(e.x,e.y,e.z,fx*.75,fy*.75,fz*.75,rx*.025,0,rz*.025,15,0,0,1,1,0x75b6ab);
            quad(e.x,e.y,e.z,fx*.75,fy*.75,fz*.75,ux*.025,uy*.025,uz*.025,15,0,0,1,1,0x75b6ab);
            for(int side=-1;side<=1;side++) quad(e.x+fx*.6+rx*side*.12,e.y+fy*.6,e.z+fz*.6+rz*side*.12,
                    fx*.22,fy*.22,fz*.22,rx*.035,0,rz*.035,15,0,0,1,1,0xa1d2c6);
        } else {
            int tile=e.type==ProjectileKind.SPECTRAL_ARROW?11:10;
            quad(e.x,e.y,e.z,fx*.5,fy*.5,fz*.5,rx*.15625,0,rz*.15625,tile,0,0,1,.15625f,0xffffff);
            quad(e.x,e.y,e.z,fx*.5,fy*.5,fz*.5,ux*.15625,uy*.15625,uz*.15625,tile,0,0,1,.15625f,0xffffff);
        }
    }
    private void quad(double x,double y,double z,double ax,double ay,double az,
                      double bx,double by,double bz,int tile,float u0,float v0,float u1,float v1,int color) {
        if(vertices.remaining()<6*FLOATS) return;
        vertex(x-ax-bx,y-ay-by,z-az-bz,tile,u0,v1,color);
        vertex(x+ax-bx,y+ay-by,z+az-bz,tile,u1,v1,color);
        vertex(x+ax+bx,y+ay+by,z+az+bz,tile,u1,v0,color);
        vertex(x-ax-bx,y-ay-by,z-az-bz,tile,u0,v1,color);
        vertex(x+ax+bx,y+ay+by,z+az+bz,tile,u1,v0,color);
        vertex(x-ax+bx,y-ay+by,z-az+bz,tile,u0,v0,color);
    }
    private void vertex(double x,double y,double z,int tile,float u,float v,int color) {
        vertices.put((float)x).put((float)y).put((float)z)
                .put((tile%8+u)/8).put((tile/8+v)/8)
                .put((color>>16&255)/255f).put((color>>8&255)/255f).put((color&255)/255f).put(vertexAlpha);
    }
    private static int shader(int type,String text) {
        int shader=GL33.glCreateShader(type); GL33.glShaderSource(shader,ScreenEffects.shader(type,text)); GL33.glCompileShader(shader);
        if(GL33.glGetShaderi(shader,GL33.GL_COMPILE_STATUS)==0) throw new IllegalStateException(GL33.glGetShaderInfoLog(shader));
        return shader;
    }
    public void close() {
        if(program!=0)ScreenEffects.deleteProgram(program); if(vao!=0)GL33.glDeleteVertexArrays(vao);
        if(vbo!=0)GL33.glDeleteBuffers(vbo); if(atlas!=0)GL33.glDeleteTextures(atlas);
        program=vao=vbo=atlas=0;
    }
    private static final String VERTEX="#version 150 core\n"
            +"in vec3 aPosition; in vec2 aUv; in vec4 aColor; out vec2 vUv; out vec4 vColor; out vec3 vWorld;\n"
            +"uniform vec3 uCamera; uniform vec2 uRotation; uniform float uAspect,uProjection;\n"
            +"void main(){ vec3 p=aPosition-uCamera; float y=radians(-uRotation.x);"
            +"p=vec3(cos(y)*p.x-sin(y)*p.z,p.y,sin(y)*p.x+cos(y)*p.z); p.x=-p.x;p.z=-p.z;"
            +"float x=radians(uRotation.y);p=vec3(p.x,cos(x)*p.y-sin(x)*p.z,sin(x)*p.y+cos(x)*p.z);"
            +"float n=.05,f=192.;gl_Position=vec4(p.x*uProjection/uAspect,p.y*uProjection,-(f+n)/(f-n)*p.z-2.*f*n/(f-n),-p.z);"
            +"vUv=aUv;vColor=aColor;vWorld=aPosition;}";
    private static final String FRAGMENT="#version 150 core\n"
            +"in vec2 vUv;in vec4 vColor;in vec3 vWorld;out vec4 fragColor;uniform sampler2D uAtlas;"
            +"uniform vec3 uCamera;uniform float uMedium;void main(){vec4 c=texture(uAtlas,vUv)*vColor;if(c.a<.1)discard;"
            +"fragColor=vec4(nhFog(c.rgb,vWorld,uCamera),c.a);}";
}
