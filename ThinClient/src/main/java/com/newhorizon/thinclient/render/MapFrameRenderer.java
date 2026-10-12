package com.newhorizon.thinclient.render;
import com.newhorizon.thinclient.world.*;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL33;
import java.nio.*;
import java.util.*;

/** One bounded, indexed atlas for server map art. Unchanged maps need no uploads. */
final class MapFrameRenderer implements AutoCloseable {
    private static final int CAPACITY=256,ATLAS=2048;
    private final MapState maps;
    private final EntityTracker.Renderable[] snapshots=new EntityTracker.Renderable[CAPACITY];
    private final LinkedHashMap<Integer,Tile> tiles=new LinkedHashMap<>(16,.75f,true);
    private final LinkedHashMap<String,VanillaItemTag> tags=new LinkedHashMap<String,VanillaItemTag>(16,.75f,true){protected boolean removeEldestEntry(Map.Entry<String,VanillaItemTag> e){return size()>256;}};
    private final ByteBuffer pixels=BufferUtils.createByteBuffer(128*128);
    private final FloatBuffer quadVertices=BufferUtils.createFloatBuffer(20),vertices=BufferUtils.createFloatBuffer(CAPACITY*6*5);
    private int program,vao,vbo,atlas,palette,frame,cameraUniform,rotationUniform,aspectUniform,projectionUniform;
    int diagnosticMaps,diagnosticUploads,diagnosticDraws;
    private static final class Tile {int slot,last;long revision;Tile(int slot){this.slot=slot;}}
    MapFrameRenderer(MapState maps){this.maps=maps;for(int i=0;i<snapshots.length;i++)snapshots[i]=new EntityTracker.Renderable();}
    void initializeGl(){
        int vs=shader(GL33.GL_VERTEX_SHADER,VERTEX),fs=shader(GL33.GL_FRAGMENT_SHADER,FRAGMENT);
        program=GL33.glCreateProgram();GL33.glAttachShader(program,vs);GL33.glAttachShader(program,fs);GL33.glBindAttribLocation(program,0,"aPosition");GL33.glBindAttribLocation(program,1,"aUv");GL33.glLinkProgram(program);GL33.glDeleteShader(vs);GL33.glDeleteShader(fs);
        if(GL33.glGetProgrami(program,GL33.GL_LINK_STATUS)==0)throw new IllegalStateException(GL33.glGetProgramInfoLog(program));
        cameraUniform=GL33.glGetUniformLocation(program,"uCamera");rotationUniform=GL33.glGetUniformLocation(program,"uRotation");aspectUniform=GL33.glGetUniformLocation(program,"uAspect");projectionUniform=GL33.glGetUniformLocation(program,"uProjection");
        GL33.glUseProgram(program);GL33.glUniform1i(GL33.glGetUniformLocation(program,"uMap"),0);GL33.glUniform1i(GL33.glGetUniformLocation(program,"uPalette"),1);
        vao=GL33.glGenVertexArrays();vbo=GL33.glGenBuffers();GL33.glBindVertexArray(vao);GL33.glBindBuffer(GL33.GL_ARRAY_BUFFER,vbo);GL33.glBufferData(GL33.GL_ARRAY_BUFFER,(long)vertices.capacity()*4,GL33.GL_STREAM_DRAW);
        GL33.glEnableVertexAttribArray(0);GL33.glVertexAttribPointer(0,3,GL33.GL_FLOAT,false,20,0);GL33.glEnableVertexAttribArray(1);GL33.glVertexAttribPointer(1,2,GL33.GL_FLOAT,false,20,12);
        atlas=GL33.glGenTextures();GL33.glActiveTexture(GL33.GL_TEXTURE0);GL33.glBindTexture(GL33.GL_TEXTURE_2D,atlas);
        GL33.glTexImage2D(GL33.GL_TEXTURE_2D,0,GL33.GL_R8,ATLAS,ATLAS,0,GL33.GL_RED,GL33.GL_UNSIGNED_BYTE,(ByteBuffer)null);textureParameters();
        ByteBuffer colors=BufferUtils.createByteBuffer(256*4);for(int i=0;i<256;i++){int c=MapState.color(i);colors.put((byte)(c>>16)).put((byte)(c>>8)).put((byte)c).put((byte)(c>>>24));}colors.flip();
        palette=GL33.glGenTextures();GL33.glActiveTexture(GL33.GL_TEXTURE1);GL33.glBindTexture(GL33.GL_TEXTURE_2D,palette);GL33.glTexImage2D(GL33.GL_TEXTURE_2D,0,GL33.GL_RGBA8,256,1,0,GL33.GL_RGBA,GL33.GL_UNSIGNED_BYTE,colors);textureParameters();GL33.glActiveTexture(GL33.GL_TEXTURE0);
    }
    private static void textureParameters(){GL33.glTexParameteri(GL33.GL_TEXTURE_2D,GL33.GL_TEXTURE_MIN_FILTER,GL33.GL_NEAREST);GL33.glTexParameteri(GL33.GL_TEXTURE_2D,GL33.GL_TEXTURE_MAG_FILTER,GL33.GL_NEAREST);GL33.glTexParameteri(GL33.GL_TEXTURE_2D,GL33.GL_TEXTURE_WRAP_S,GL33.GL_CLAMP_TO_EDGE);GL33.glTexParameteri(GL33.GL_TEXTURE_2D,GL33.GL_TEXTURE_WRAP_T,GL33.GL_CLAMP_TO_EDGE);}
    void draw(EntityTracker tracker,long now,float x,float y,float z,float yaw,float pitch,float aspect,float projection,int local){int count=tracker.snapshotMapFrames(snapshots,now,x,y,z,64,local);drawSnapshots(snapshots,count,x,y,z,yaw,pitch,aspect,projection);}
    void drawSnapshots(EntityTracker.Renderable[] items,int count,float x,float y,float z,float yaw,float pitch,float aspect,float projection){
        frame++;diagnosticMaps=diagnosticUploads=diagnosticDraws=0;vertices.clear();
        double cy=Math.cos(Math.toRadians(-yaw)),sy=Math.sin(Math.toRadians(-yaw)),cp=Math.cos(Math.toRadians(pitch)),sp=Math.sin(Math.toRadians(pitch));
        GL33.glActiveTexture(GL33.GL_TEXTURE0);GL33.glBindTexture(GL33.GL_TEXTURE_2D,atlas);
        for(int i=0;i<count&&vertices.remaining()>=30;i++){
            EntityTracker.Renderable e=items[i];if(e.type!=43&&e.type!=56)continue;
            double dx=e.x-x,dy=e.y-y,dz=e.z-z,rx=cy*dx-sy*dz,rz=sy*dx+cy*dz,py=cp*dy+sp*rz,depth=cp*rz-sp*dy;
            // Conservative bounds around the entire tile: retain edge/intersecting quads.
            if(depth < -2 || Math.abs(rx)>Math.max(0,depth)*aspect/projection+2 || Math.abs(py)>Math.max(0,depth)/projection+2)continue;
            VanillaItemTag tag=tags.get(e.itemTag);if(tag==null){tag=VanillaItemTag.read(e.itemTag);tags.put(e.itemTag,tag);}if(!tag.hasMap)continue;
            diagnosticMaps++;Tile t=tiles.get(tag.mapId);long rev=maps.copyIndices(tag.mapId,t==null?0:t.revision,pixels);if(rev<0)continue;
            if(t==null){int slot=tiles.size();if(slot==CAPACITY){Iterator<Tile> it=tiles.values().iterator();Tile victim=null;while(it.hasNext()){Tile v=it.next();if(v.last!=frame){victim=v;it.remove();break;}}if(victim==null)continue;slot=victim.slot;}t=new Tile(slot);tiles.put(tag.mapId,t);}
            if(t.revision!=rev){GL33.glTexSubImage2D(GL33.GL_TEXTURE_2D,0,(t.slot&15)*128,(t.slot>>4)*128,128,128,GL33.GL_RED,GL33.GL_UNSIGNED_BYTE,pixels);t.revision=rev;diagnosticUploads++;}
            t.last=frame;quad(e,quadVertices);
            for(int k=0;k<6;k++){int corner=TRIANGLES[k],offset=corner*5;vertices.put(quadVertices.get(offset)).put(quadVertices.get(offset+1)).put(quadVertices.get(offset+2));vertices.put(((t.slot&15)*128+.5f+quadVertices.get(offset+3)*127)/ATLAS).put(((t.slot>>4)*128+.5f+quadVertices.get(offset+4)*127)/ATLAS);}
        }
        if(vertices.position()==0)return;
        boolean cull=GL33.glIsEnabled(GL33.GL_CULL_FACE),blend=GL33.glIsEnabled(GL33.GL_BLEND),depth=GL33.glIsEnabled(GL33.GL_DEPTH_TEST),mask=GL33.glGetBoolean(GL33.GL_DEPTH_WRITEMASK);
        ScreenEffects.bind(program);GL33.glUniform3f(cameraUniform,x,y,z);GL33.glUniform2f(rotationUniform,yaw,pitch);GL33.glUniform1f(aspectUniform,aspect);GL33.glUniform1f(projectionUniform,projection);
        GL33.glActiveTexture(GL33.GL_TEXTURE1);int previousTexture1=GL33.glGetInteger(GL33.GL_TEXTURE_BINDING_2D);GL33.glBindTexture(GL33.GL_TEXTURE_2D,palette);GL33.glActiveTexture(GL33.GL_TEXTURE0);
        int n=vertices.position()/5;vertices.flip();GL33.glBindVertexArray(vao);GL33.glBindBuffer(GL33.GL_ARRAY_BUFFER,vbo);GL33.glBufferData(GL33.GL_ARRAY_BUFFER,vertices,GL33.GL_STREAM_DRAW);
        GL33.glDisable(GL33.GL_CULL_FACE);GL33.glDisable(GL33.GL_BLEND);GL33.glEnable(GL33.GL_DEPTH_TEST);GL33.glDepthMask(true);GL33.glDrawArrays(GL33.GL_TRIANGLES,0,n);diagnosticDraws=1;
        GL33.glActiveTexture(GL33.GL_TEXTURE1);GL33.glBindTexture(GL33.GL_TEXTURE_2D,previousTexture1);GL33.glActiveTexture(GL33.GL_TEXTURE0);
        GL33.glDepthMask(mask);if(cull)GL33.glEnable(GL33.GL_CULL_FACE);if(blend)GL33.glEnable(GL33.GL_BLEND);if(!depth)GL33.glDisable(GL33.GL_DEPTH_TEST);
    }
    private static final int[] TRIANGLES={0,1,2,2,1,3};
    static void quad(EntityTracker.Renderable e,FloatBuffer out){
        int d=e.frameDirection;if(d<0||d>5)d=2;
        float[] n=NORMAL[d],r=RIGHT[d],up=UP[d];float offset=e.invisible?-.0234375f:.0390625f;
        out.clear();for(int i=0;i<4;i++){float u=(i&1),v=(i>>1),sx=u-.5f,sy=.5f-v;
            for(int turn=0;turn<(e.appearance[9]&3);turn++){float old=sx;sx=-sy;sy=old;}
            out.put(e.x+n[0]*offset+r[0]*sx+up[0]*sy).put(e.y+n[1]*offset+r[1]*sx+up[1]*sy).put(e.z+n[2]*offset+r[2]*sx+up[2]*sy).put(u).put(v);
        }out.flip();
    }
    private static final float[][] NORMAL={{0,-1,0},{0,1,0},{0,0,-1},{0,0,1},{-1,0,0},{1,0,0}},RIGHT={{1,0,0},{1,0,0},{-1,0,0},{1,0,0},{0,0,1},{0,0,-1}},UP={{0,0,1},{0,0,-1},{0,1,0},{0,1,0},{0,1,0},{0,1,0}};
    private static int shader(int type,String text){int s=GL33.glCreateShader(type);GL33.glShaderSource(s,ScreenEffects.shader(type,text));GL33.glCompileShader(s);if(GL33.glGetShaderi(s,GL33.GL_COMPILE_STATUS)==0)throw new IllegalStateException(GL33.glGetShaderInfoLog(s));return s;}
    public void close(){tiles.clear();tags.clear();if(program!=0)ScreenEffects.deleteProgram(program);if(vao!=0)GL33.glDeleteVertexArrays(vao);if(vbo!=0)GL33.glDeleteBuffers(vbo);if(atlas!=0)GL33.glDeleteTextures(atlas);if(palette!=0)GL33.glDeleteTextures(palette);}
    private static final String VERTEX="#version 150 core\n in vec3 aPosition;in vec2 aUv;out vec2 vUv;uniform vec3 uCamera;uniform vec2 uRotation;uniform float uAspect,uProjection;void main(){vec3 p=aPosition-uCamera;float y=radians(-uRotation.x);p=vec3(cos(y)*p.x-sin(y)*p.z,p.y,sin(y)*p.x+cos(y)*p.z);p.x=-p.x;p.z=-p.z;float x=radians(uRotation.y);p=vec3(p.x,cos(x)*p.y-sin(x)*p.z,sin(x)*p.y+cos(x)*p.z);float n=.05,f=192.;gl_Position=vec4(p.x*uProjection/uAspect,p.y*uProjection,-(f+n)/(f-n)*p.z-2.*f*n/(f-n),-p.z);vUv=aUv;}";
    private static final String FRAGMENT="#version 150 core\n in vec2 vUv;out vec4 fragColor;uniform sampler2D uMap,uPalette;void main(){float index=texture(uMap,vUv).r;fragColor=texture(uPalette,vec2((index*255.0+.5)/256.0,.5));if(fragColor.a<.1)discard;}";
}
