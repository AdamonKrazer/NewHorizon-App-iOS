package com.newhorizon.thinclient.render;

import com.newhorizon.thinclient.world.*;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL33;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;

/** Billboard names drawn in the world frame, before the hand clears world depth. */
final class WorldLabelRenderer implements AutoCloseable {
    private static final int STRIDE=32,LAYOUT_STRIDE=20,MAX_QUADS=8192,MAX_LAYOUT_BYTES=2*1024*1024;
    private final EntityTracker.Renderable[] entities=new EntityTracker.Renderable[512];
    private final ByteBuffer vertices=BufferUtils.createByteBuffer(MAX_QUADS*6*STRIDE);
    private final Map<Integer,Glyph> glyphs=new HashMap<>();
    private final LinkedHashMap<String,Layout> cache=new LinkedHashMap<>(128,.75f,true);
    private int layoutBytes,logicalBytes;
    private int program,vao,vbo,texture,cameraUniform,rotationUniform,aspectUniform,projectionUniform;
    private boolean cullLabels;
    private double cameraX,cameraY,cameraZ,cy,sy,cp,sp,projectionX,projectionY;
    int diagnosticLabels,diagnosticCulled,diagnosticBuilds,diagnosticVertices;
    WorldLabelRenderer(){for(int i=0;i<entities.length;i++)entities[i]=new EntityTracker.Renderable();}
    void initializeGl(){
        try(DataInputStream in=new DataInputStream(getClass().getResourceAsStream("/assets/newhorizon/font/vanilla.bin"))){
            int count=in.readInt();if(count<1||count>4096)throw new IOException("Font size");
            for(int i=0;i<count;i++){int code=in.readInt();Glyph g=new Glyph();g.u=in.readUnsignedShort()/1024f;g.v=in.readUnsignedShort()/1024f;g.du=in.readUnsignedShort()/1024f;g.dv=in.readUnsignedShort()/1024f;g.w=in.readFloat();g.h=in.readFloat();g.top=in.readFloat();g.advance=in.readFloat();glyphs.put(code,g);}
            BufferedImage im=ImageIO.read(getClass().getResourceAsStream("/assets/newhorizon/font/vanilla.png"));
            ByteBuffer pixels=BufferUtils.createByteBuffer(im.getWidth()*im.getHeight()*4);
            for(int y=0;y<im.getHeight();y++)for(int x=0;x<im.getWidth();x++){int c=im.getRGB(x,y);pixels.put((byte)(c>>16)).put((byte)(c>>8)).put((byte)c).put((byte)(c>>24));}pixels.flip();
            texture=GL33.glGenTextures();GL33.glActiveTexture(GL33.GL_TEXTURE0);GL33.glBindTexture(GL33.GL_TEXTURE_2D,texture);
            GL33.glTexImage2D(GL33.GL_TEXTURE_2D,0,GL33.GL_RGBA8,im.getWidth(),im.getHeight(),0,GL33.GL_RGBA,GL33.GL_UNSIGNED_BYTE,pixels);
            GL33.glTexParameteri(GL33.GL_TEXTURE_2D,GL33.GL_TEXTURE_MIN_FILTER,GL33.GL_NEAREST);GL33.glTexParameteri(GL33.GL_TEXTURE_2D,GL33.GL_TEXTURE_MAG_FILTER,GL33.GL_NEAREST);
            GL33.glTexParameteri(GL33.GL_TEXTURE_2D,GL33.GL_TEXTURE_WRAP_S,GL33.GL_CLAMP_TO_EDGE);GL33.glTexParameteri(GL33.GL_TEXTURE_2D,GL33.GL_TEXTURE_WRAP_T,GL33.GL_CLAMP_TO_EDGE);
        }catch(IOException e){throw new IllegalStateException("Vanilla name font",e);}
        int vs=shader(GL33.GL_VERTEX_SHADER,VERTEX),fs=shader(GL33.GL_FRAGMENT_SHADER,FRAGMENT);
        program=GL33.glCreateProgram();GL33.glAttachShader(program,vs);GL33.glAttachShader(program,fs);
        String[] attrs={"aAnchor","aOffset","aUv","aColor"};for(int i=0;i<attrs.length;i++)GL33.glBindAttribLocation(program,i,attrs[i]);
        GL33.glLinkProgram(program);GL33.glDeleteShader(vs);GL33.glDeleteShader(fs);
        if(GL33.glGetProgrami(program,GL33.GL_LINK_STATUS)==0)throw new IllegalStateException(GL33.glGetProgramInfoLog(program));
        cameraUniform=GL33.glGetUniformLocation(program,"uCamera");rotationUniform=GL33.glGetUniformLocation(program,"uRotation");
        aspectUniform=GL33.glGetUniformLocation(program,"uAspect");projectionUniform=GL33.glGetUniformLocation(program,"uProjection");
        GL33.glUseProgram(program);GL33.glUniform1i(GL33.glGetUniformLocation(program,"uFont"),0);
        vao=GL33.glGenVertexArrays();vbo=GL33.glGenBuffers();GL33.glBindVertexArray(vao);GL33.glBindBuffer(GL33.GL_ARRAY_BUFFER,vbo);
        GL33.glBufferData(GL33.GL_ARRAY_BUFFER,(long)vertices.capacity(),GL33.GL_STREAM_DRAW);
        int[] sizes={3,2,2,4};int[] offsets={0,12,20,28};for(int i=0;i<4;i++){GL33.glEnableVertexAttribArray(i);GL33.glVertexAttribPointer(i,sizes[i],i==3?GL33.GL_UNSIGNED_BYTE:GL33.GL_FLOAT,i==3,STRIDE,offsets[i]);}
    }
    void draw(EntityTracker tracker,PlayerListState players,ServerHudState hud,long now,float x,float y,float z,float yaw,float pitch,float aspect,float projection,int local){
        begin();camera(x,y,z,yaw,pitch,aspect,projection);int count=tracker.snapshotLabels(entities,now,x,y,z,64,local),drawn=0;
        for(int i=0;i<count&&drawn<64;i++){
            EntityTracker.Renderable e=entities[i];String text="";
            if(e.type==101)text=e.displayText;
            else if(e.nameVisible&&!e.customName.isEmpty())text=e.customName;
            else if(e.player&&!e.invisible){PlayerListState.Entry p=players.get(e.profileId);if(p!=null)text=hud.label(p.name,players.localName());}
            if(text.isEmpty()||text.length()>4096)continue;
            if(e.sneaking&&distance2(e,x,y,z)>1024)continue;
            add(text,e.x,labelY(e),e.z);drawn++;
        }
        flush(x,y,z,yaw,pitch,aspect,projection);
    }
    private static float distance2(EntityTracker.Renderable e,float x,float y,float z){return (e.x-x)*(e.x-x)+(e.y-y)*(e.y-y)+(e.z-z)*(e.z-z);}
    static float labelY(EntityTracker.Renderable e){
        if(e.type==101)return e.y;
        float h=e.labelHeight;if(e.type==2){int flags=e.appearance[15];h=(flags&16)!=0?0:(flags&1)!=0?.9875f:1.975f;}
        return e.y+h+.5f;
    }
    void begin(){vertices.clear();logicalBytes=0;cullLabels=false;diagnosticLabels=diagnosticCulled=diagnosticBuilds=diagnosticVertices=0;}
    private void camera(float x,float y,float z,float yaw,float pitch,float aspect,float projection){
        cameraX=x;cameraY=y;cameraZ=z;cy=Math.cos(Math.toRadians(-yaw));sy=Math.sin(Math.toRadians(-yaw));
        cp=Math.cos(Math.toRadians(pitch));sp=Math.sin(Math.toRadians(pitch));projectionX=projection/aspect;projectionY=projection;cullLabels=true;
    }
    void add(String json,float x,float y,float z){
        Layout layout=cache.get(json);if(layout==null){layout=layout(LabelText.parse(json));cache.put(json,layout);layoutBytes+=layout.data.length;diagnosticBuilds++;
            Iterator<Layout> oldest=cache.values().iterator();while(cache.size()>128||layoutBytes>MAX_LAYOUT_BYTES){layoutBytes-=oldest.next().data.length;oldest.remove();}}
        diagnosticLabels++;
        // Invisible labels still consume the old logical capacity, preserving overflow/order.
        int length=Math.min(layout.data.length,(vertices.capacity()-logicalBytes)/(6*STRIDE)*(6*LAYOUT_STRIDE));
        logicalBytes+=length/LAYOUT_STRIDE*STRIDE;
        if(cullLabels&&!visible(layout,x,y,z)){diagnosticCulled++;return;}
        for(int offset=0;offset<length;offset+=LAYOUT_STRIDE)vertices.putFloat(x).putFloat(y).putFloat(z).put(layout.data,offset,LAYOUT_STRIDE);
    }
    private boolean visible(Layout layout,float x,float y,float z){
        double dx=x-cameraX,dy=y-cameraY,dz=z-cameraZ,rx=cy*dx-sy*dz,rz=sy*dx+cy*dz;
        double px=-rx,py=cp*dy+sp*rz,depth=cp*rz-sp*dy;
        return ScreenEffects.clipRectVisible((px+layout.minX)*projectionX,(py+layout.minY)*projectionY,
                (px+layout.maxX)*projectionX,(py+layout.maxY)*projectionY,depth);
    }
    private Layout layout(LabelText text){
        Layout layout=new Layout();
        ByteBuffer data=ByteBuffer.allocate((text.size*4+8)*6*LAYOUT_STRIDE).order(ByteOrder.nativeOrder());
        int start=0,line=0;
        while(start<text.size&&line<8){int end=start;float width=0;while(end<text.size&&text.code[end]!='\n'){Glyph g=glyph(text.code[end]);width+=g.advance+((text.style[end]&1)!=0?1:0);end++;}
            float cursor=-width/2,baseline=line*10;
            quad(data,layout,cursor-1,baseline-1,width+2,10,0,1,1,0,0,0x40000000);
            for(int i=start;i<end;i++){
                Glyph g=glyph(text.code[i]);int flags=text.style[i],color=0xff000000|text.color[i];float slant=(flags&2)!=0?1:0;
                quad(data,layout,cursor,baseline+g.top,g.w,g.h,slant,g.u,g.v,g.du,g.dv,color);
                if((flags&1)!=0)quad(data,layout,cursor+1,baseline+g.top,g.w,g.h,slant,g.u,g.v,g.du,g.dv,color);
                float advance=g.advance+((flags&1)!=0?1:0);
                if((flags&4)!=0)quad(data,layout,cursor,baseline+8,advance,1,0,1,1,0,0,color);
                if((flags&8)!=0)quad(data,layout,cursor,baseline+4,advance,1,0,1,1,0,0,color);
                cursor+=advance;
            }start=end+1;line++;
        }
        layout.data=Arrays.copyOf(data.array(),data.position());return layout;
    }
    private Glyph glyph(int code){Glyph g=glyphs.get(code);return g==null?glyphs.get((int)'?'):g;}
    private static void quad(ByteBuffer data,Layout layout,float x,float y,float w,float h,float italic,float u,float v,float du,float dv,int color){
        vertex(data,layout,x+italic,y,u,v,color);vertex(data,layout,x-italic,y+h,u,v+dv,color);vertex(data,layout,x+w-italic,y+h,u+du,v+dv,color);
        vertex(data,layout,x+italic,y,u,v,color);vertex(data,layout,x+w-italic,y+h,u+du,v+dv,color);vertex(data,layout,x+w+italic,y,u+du,v,color);
    }
    private static void vertex(ByteBuffer data,Layout layout,float x,float y,float u,float v,int c){
        x*=.025f;y*=-.025f;layout.minX=Math.min(layout.minX,x);layout.minY=Math.min(layout.minY,y);layout.maxX=Math.max(layout.maxX,x);layout.maxY=Math.max(layout.maxY,y);
        data.putFloat(x).putFloat(y).putFloat(u).putFloat(v).put((byte)(c>>16)).put((byte)(c>>8)).put((byte)c).put((byte)(c>>24));
    }
    void flush(float x,float y,float z,float yaw,float pitch,float aspect,float projection){
        int n=vertices.position()/STRIDE;diagnosticVertices=n;if(n==0)return;vertices.flip();
        boolean blend=GL33.glIsEnabled(GL33.GL_BLEND),cull=GL33.glIsEnabled(GL33.GL_CULL_FACE),depth=GL33.glIsEnabled(GL33.GL_DEPTH_TEST),mask=GL33.glGetBoolean(GL33.GL_DEPTH_WRITEMASK);
        int src=GL33.glGetInteger(GL33.GL_BLEND_SRC_RGB),dst=GL33.glGetInteger(GL33.GL_BLEND_DST_RGB),srcA=GL33.glGetInteger(GL33.GL_BLEND_SRC_ALPHA),dstA=GL33.glGetInteger(GL33.GL_BLEND_DST_ALPHA),func=GL33.glGetInteger(GL33.GL_DEPTH_FUNC);
        ScreenEffects.bind(program);GL33.glUniform3f(cameraUniform,x,y,z);GL33.glUniform2f(rotationUniform,yaw,pitch);
        GL33.glUniform1f(aspectUniform,aspect);GL33.glUniform1f(projectionUniform,projection);
        GL33.glActiveTexture(GL33.GL_TEXTURE0);GL33.glBindTexture(GL33.GL_TEXTURE_2D,texture);
        GL33.glBindVertexArray(vao);GL33.glBindBuffer(GL33.GL_ARRAY_BUFFER,vbo);
        // Prior frames may still consume the previous batch; replace storage before writing.
        GL33.glBufferData(GL33.GL_ARRAY_BUFFER,vertices,GL33.GL_STREAM_DRAW);
        GL33.glEnable(GL33.GL_BLEND);GL33.glBlendFunc(GL33.GL_SRC_ALPHA,GL33.GL_ONE_MINUS_SRC_ALPHA);GL33.glDisable(GL33.GL_CULL_FACE);GL33.glEnable(GL33.GL_DEPTH_TEST);GL33.glDepthFunc(GL33.GL_LEQUAL);GL33.glDepthMask(false);
        GL33.glDrawArrays(GL33.GL_TRIANGLES,0,n);
        GL33.glDepthMask(mask);GL33.glDepthFunc(func);GL33.glBlendFuncSeparate(src,dst,srcA,dstA);if(!blend)GL33.glDisable(GL33.GL_BLEND);if(cull)GL33.glEnable(GL33.GL_CULL_FACE);if(!depth)GL33.glDisable(GL33.GL_DEPTH_TEST);
    }
    private static int shader(int type,String code){int id=GL33.glCreateShader(type);GL33.glShaderSource(id,ScreenEffects.shader(type,code));GL33.glCompileShader(id);if(GL33.glGetShaderi(id,GL33.GL_COMPILE_STATUS)==0)throw new IllegalStateException(GL33.glGetShaderInfoLog(id));return id;}
    public void close(){if(program!=0)ScreenEffects.deleteProgram(program);if(vao!=0)GL33.glDeleteVertexArrays(vao);if(vbo!=0)GL33.glDeleteBuffers(vbo);if(texture!=0)GL33.glDeleteTextures(texture);program=vao=vbo=texture=0;cache.clear();layoutBytes=0;glyphs.clear();}
    private static final class Layout{byte[] data;float minX=Float.POSITIVE_INFINITY,minY=Float.POSITIVE_INFINITY,maxX=Float.NEGATIVE_INFINITY,maxY=Float.NEGATIVE_INFINITY;}
    private static final class Glyph{float u,v,du,dv,w,h,top,advance;}
    private static final String VERTEX="#version 150 core\n"
        +"in vec3 aAnchor;in vec2 aOffset,aUv;in vec4 aColor;out vec2 vUv;out vec4 vColor;uniform vec3 uCamera;uniform vec2 uRotation;uniform float uAspect,uProjection;"
        +"void main(){vec3 p=aAnchor-uCamera;float y=radians(-uRotation.x);p=vec3(cos(y)*p.x-sin(y)*p.z,p.y,sin(y)*p.x+cos(y)*p.z);p.x=-p.x;p.z=-p.z;"
        +"float x=radians(uRotation.y);p=vec3(p.x,cos(x)*p.y-sin(x)*p.z,sin(x)*p.y+cos(x)*p.z);p.xy+=aOffset;float n=.05,f=192.;"
        +"gl_Position=vec4(p.x*uProjection/uAspect,p.y*uProjection,-(f+n)/(f-n)*p.z-2.*f*n/(f-n),-p.z);vUv=aUv;vColor=aColor;}";
    private static final String FRAGMENT="#version 150 core\n"
        +"in vec2 vUv;in vec4 vColor;out vec4 fragColor;uniform sampler2D uFont;void main(){fragColor=texture(uFont,vUv)*vColor;if(fragColor.a<.01)discard;}";
}
