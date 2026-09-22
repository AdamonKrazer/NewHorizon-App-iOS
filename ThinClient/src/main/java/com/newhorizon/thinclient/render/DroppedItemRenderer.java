package com.newhorizon.thinclient.render;

import com.newhorizon.thinclient.world.EntityTracker;
import com.newhorizon.thinclient.world.VanillaBlockTextures;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL33;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.io.*;
import java.nio.charset.StandardCharsets;

/** 64 dropped stacks, original sprites and shared block atlas. 64KiB item atlas. */
final class DroppedItemRenderer implements AutoCloseable {
    private static final int MAX=64,STRIDE=10;
    private static final String[] NAMES=names();
    private final FloatBuffer vertices=BufferUtils.createFloatBuffer((MAX+256)*36*STRIDE);
    private final EquipmentPose equipmentPose=new EquipmentPose();
    private final FloatBuffer block=BufferUtils.createFloatBuffer(36*9);
    private final ByteBuffer upload=BufferUtils.createByteBuffer(16*16*4);
    private final String[] keys=new String[MAX];
    private final long[] ages=new long[MAX];
    private final float[] matrix=new float[16];
    private final float[] itemLight=new float[3];
    private int texture,program,vao,vbo;
    private long frame;
    void initializeGl() {
        texture=GL33.glGenTextures();GL33.glActiveTexture(GL33.GL_TEXTURE0);GL33.glBindTexture(GL33.GL_TEXTURE_2D,texture);
        GL33.glTexParameteri(GL33.GL_TEXTURE_2D,GL33.GL_TEXTURE_MIN_FILTER,GL33.GL_NEAREST);
        GL33.glTexParameteri(GL33.GL_TEXTURE_2D,GL33.GL_TEXTURE_MAG_FILTER,GL33.GL_NEAREST);
        GL33.glTexParameteri(GL33.GL_TEXTURE_2D,GL33.GL_TEXTURE_WRAP_S,GL33.GL_CLAMP_TO_EDGE);
        GL33.glTexParameteri(GL33.GL_TEXTURE_2D,GL33.GL_TEXTURE_WRAP_T,GL33.GL_CLAMP_TO_EDGE);
        GL33.glTexImage2D(GL33.GL_TEXTURE_2D,0,GL33.GL_RGBA8,128,128,0,GL33.GL_RGBA,GL33.GL_UNSIGNED_BYTE,0L);
        int vs=shader(GL33.GL_VERTEX_SHADER,VERTEX),fs=shader(GL33.GL_FRAGMENT_SHADER,FRAGMENT);
        program=GL33.glCreateProgram();GL33.glAttachShader(program,vs);GL33.glAttachShader(program,fs);
        GL33.glBindAttribLocation(program,0,"aPosition");GL33.glBindAttribLocation(program,1,"aColor");
        GL33.glBindAttribLocation(program,2,"aUv");GL33.glBindAttribLocation(program,3,"aBlock");GL33.glLinkProgram(program);
        GL33.glDeleteShader(vs);GL33.glDeleteShader(fs);
        if(GL33.glGetProgrami(program,GL33.GL_LINK_STATUS)==0)throw new IllegalStateException(GL33.glGetProgramInfoLog(program));
        vao=GL33.glGenVertexArrays();vbo=GL33.glGenBuffers();GL33.glBindVertexArray(vao);GL33.glBindBuffer(GL33.GL_ARRAY_BUFFER,vbo);
        GL33.glBufferData(GL33.GL_ARRAY_BUFFER,(long)vertices.capacity()*4,GL33.GL_STREAM_DRAW);
        int[] sizes={3,4,2,1},offsets={0,3,7,9};
        for(int i=0;i<4;i++){GL33.glEnableVertexAttribArray(i);GL33.glVertexAttribPointer(i,sizes[i],GL33.GL_FLOAT,false,STRIDE*4,offsets[i]*4L);}
    }
    void draw(EntityTracker.Renderable[] entities,int count,float x,float y,float z,float yaw,float pitch,float aspect,float projection,float medium) {
        vertices.clear();frame++;int added=0;
        GL33.glActiveTexture(GL33.GL_TEXTURE0);GL33.glBindTexture(GL33.GL_TEXTURE_2D,texture);
        double time=System.nanoTime()/1e9;
        for(int i=0;i<count&&added<MAX;i++) {
            EntityTracker.Renderable entity=entities[i];if((entity.type!=54&&entity.type!=36&&entity.type!=101)||entity.invisible)continue;
            boolean fullBlock=entity.type==36||entity.type==101;
            String material=entity.type==101?"tnt":fullBlock?"falling_block":name(entity.itemId);
            if(material==null||material.equals("air"))continue;
            int start=vertices.position();SceneAtmosphere.light(entity.x,entity.y+.25f,entity.z,itemLight);
            double phase=entity.entityId*.53;float angle=(float)((time+phase)%(Math.PI*2));
            float height=(float)entity.y+.15f+(float)Math.sin(time*2+phase)*.1f;
            int state=entity.type==36?entity.blockState:VanillaBlockTextures.defaultState(material);
            // Generated model aliases (flowers, doors, signs) retain their original flat geometry.
            boolean generated=!fullBlock&&VanillaItemSprites.transform(material)!=null;
            if(state>=0&&!generated) {
                float size=fullBlock?1:.25f,c=fullBlock?1:(float)Math.cos(angle)*size,s=fullBlock?0:(float)Math.sin(angle)*size;
                java.util.Arrays.fill(matrix,0);matrix[0]=c;matrix[2]=-s;matrix[5]=size;matrix[8]=s;matrix[10]=c;matrix[15]=1;
                matrix[12]=(float)entity.x-(c+s)*.5f;matrix[13]=fullBlock?(float)entity.y:height;matrix[14]=(float)entity.z-(c-s)*.5f;
                block.clear();HeldBlockMesh.append(block,state,matrix);block.flip();
                while(block.hasRemaining()){for(int n=0;n<9;n++)vertices.put(block.get());vertices.put(1);}
            }else {
                int slot=slot(material,entity.itemTag);if(slot<0)continue;
                float halfWidth=material.equals("shield")?(.25f*12/22):.25f;
                float sideX=(float)Math.cos(angle)*halfWidth,sideZ=(float)Math.sin(angle)*halfWidth;
                float u0=(slot%8*16+.01f)/128,u1=(slot%8*16+15.99f)/128;
                float v0=(slot/8*16+.01f)/128,v1=(slot/8*16+15.99f)/128;
                float ex=(float)entity.x,ez=(float)entity.z;
                spriteVertex(ex-sideX,height,ez-sideZ,u0,v1);spriteVertex(ex+sideX,height,ez+sideZ,u1,v1);spriteVertex(ex+sideX,height+.5f,ez+sideZ,u1,v0);
                spriteVertex(ex-sideX,height,ez-sideZ,u0,v1);spriteVertex(ex+sideX,height+.5f,ez+sideZ,u1,v0);spriteVertex(ex-sideX,height+.5f,ez-sideZ,u0,v0);
            }
            for(int p=start;p<vertices.position();p+=STRIDE)for(int channel=0;channel<3;channel++)vertices.put(p+3+channel,vertices.get(p+3+channel)*itemLight[channel]);
            added++;
        }
        for(int i=0;i<count;i++)if(!entities[i].invisible&&!entities[i].sleeping)for(int hand=0;hand<2;hand++)held(entities[i],hand);
        int n=vertices.position()/STRIDE;if(n==0)return;vertices.flip();ScreenEffects.bind(program);
        GL33.glUniform1i(GL33.glGetUniformLocation(program,"uItems"),0);GL33.glUniform1i(GL33.glGetUniformLocation(program,"uBlocks"),1);
        GL33.glUniform3f(GL33.glGetUniformLocation(program,"uCamera"),x,y,z);GL33.glUniform2f(GL33.glGetUniformLocation(program,"uRotation"),yaw,pitch);
        GL33.glUniform1f(GL33.glGetUniformLocation(program,"uAspect"),aspect);GL33.glUniform1f(GL33.glGetUniformLocation(program,"uProjection"),projection);GL33.glUniform1f(GL33.glGetUniformLocation(program,"uMedium"),medium);
        GL33.glBindVertexArray(vao);GL33.glBindBuffer(GL33.GL_ARRAY_BUFFER,vbo);GL33.glBufferSubData(GL33.GL_ARRAY_BUFFER,0,vertices);
        boolean cull=GL33.glIsEnabled(GL33.GL_CULL_FACE);GL33.glDisable(GL33.GL_CULL_FACE);GL33.glDrawArrays(GL33.GL_TRIANGLES,0,n);if(cull)GL33.glEnable(GL33.GL_CULL_FACE);
    }
    private void spriteVertex(float x,float y,float z,float u,float v){vertices.put(x).put(y).put(z).put(1).put(1).put(1).put(1).put(u).put(v).put(0);}
    private static final int[] HELD_CORNERS={0,1,2,0,2,3};
    private void held(EntityTracker.Renderable e,int hand){
        String material=name(e.equipment[hand]);if(material==null||material.equals("air")||vertices.remaining()<36*STRIDE)return;
        if(!equipmentPose.prepare(e,hand,material))return;
        SceneAtmosphere.light(e.x,e.y+1,e.z,itemLight);int start=vertices.position();
        int state=VanillaBlockTextures.defaultState(material);
        if(state>=0&&VanillaItemSprites.transform(material)==null){
            System.arraycopy(equipmentPose.matrix,0,matrix,0,16);
            for(int r=0;r<3;r++)matrix[12+r]-=(matrix[r]+matrix[4+r]+matrix[8+r])*.5f;
            block.clear();HeldBlockMesh.append(block,state,matrix);block.flip();while(block.hasRemaining()){for(int k=0;k<9;k++)vertices.put(block.get());vertices.put(1);}
        }else{
            String sprite=material.equals("bow")&&e.blocking?"bow_pulling_2":material;
            int at=slot(sprite,e.equipmentTags[hand]);if(at<0)return;float[] m=equipmentPose.matrix;
            for(int side=0;side<2;side++)for(int corner:HELD_CORNERS){
                float x=corner==1||corner==2?.5f:-.5f,y=corner>=2?.5f:-.5f,z=side==0?-.03125f:.03125f;
                float u=(at%8*16+(x+.5f)*15.98f+.01f)/128,v=(at/8*16+(.5f-y)*15.98f+.01f)/128;
                spriteVertex(m[0]*x+m[4]*y+m[8]*z+m[12],m[1]*x+m[5]*y+m[9]*z+m[13],m[2]*x+m[6]*y+m[10]*z+m[14],u,v);
            }
        }
        for(int p=start;p<vertices.position();p+=STRIDE)for(int c=0;c<3;c++)vertices.put(p+3+c,vertices.get(p+3+c)*itemLight[c]);
    }
    private int slot(String material,String tag) {
        String key=material+":"+(tag==null?"":tag);int free=-1;long oldest=Long.MAX_VALUE;
        for(int i=0;i<MAX;i++) {
            if(key.equals(keys[i])){ages[i]=frame;return i;}
            if(ages[i]<oldest){oldest=ages[i];free=i;}
        }
        if(free<0||oldest==frame)return -1;
        int[] pixels=CombatItemSprites.load(material,tag);if(pixels==null)return -1;
        upload.clear();for(int pixel:pixels)upload.put((byte)(pixel>>16)).put((byte)(pixel>>8)).put((byte)pixel).put((byte)(pixel==0?0:255));upload.flip();
        GL33.glTexSubImage2D(GL33.GL_TEXTURE_2D,0,free%8*16,free/8*16,16,16,GL33.GL_RGBA,GL33.GL_UNSIGNED_BYTE,upload);
        keys[free]=key;ages[free]=frame;return free;
    }
    static String name(int id){return id>=0&&id<NAMES.length?NAMES[id]:null;}
    private static String[] names() {
        String[] result=new String[1255];
        try(InputStream stream=DroppedItemRenderer.class.getResourceAsStream("/assets/newhorizon/vanilla_item_ids_1_20_1.tsv");
            BufferedReader in=new BufferedReader(new InputStreamReader(stream,StandardCharsets.UTF_8))) {
            String line;while((line=in.readLine())!=null){String[] f=line.split("\t");result[Integer.parseInt(f[0])]=f[1];}
        }catch(IOException error){throw new ExceptionInInitializerError(error);}return result;
    }
    private static int shader(int type,String source){int id=GL33.glCreateShader(type);GL33.glShaderSource(id,ScreenEffects.shader(type,source));GL33.glCompileShader(id);if(GL33.glGetShaderi(id,GL33.GL_COMPILE_STATUS)==0)throw new IllegalStateException(GL33.glGetShaderInfoLog(id));return id;}
    public void close(){if(texture!=0)GL33.glDeleteTextures(texture);if(program!=0)ScreenEffects.deleteProgram(program);if(vao!=0)GL33.glDeleteVertexArrays(vao);if(vbo!=0)GL33.glDeleteBuffers(vbo);texture=program=vao=vbo=0;java.util.Arrays.fill(keys,null);java.util.Arrays.fill(ages,0);}
    private static final String VERTEX="#version 150 core\n"
            +"in vec3 aPosition;in vec4 aColor;in vec2 aUv;in float aBlock;out vec4 vColor;out vec2 vUv;out vec3 vWorld;flat out float vBlock;"
            +"uniform vec3 uCamera;uniform vec2 uRotation;uniform float uAspect,uProjection;"
            +"void main(){vec3 p=aPosition-uCamera;float y=radians(-uRotation.x);p=vec3(cos(y)*p.x-sin(y)*p.z,p.y,sin(y)*p.x+cos(y)*p.z);p.x=-p.x;p.z=-p.z;"
            +"float x=radians(uRotation.y);p=vec3(p.x,cos(x)*p.y-sin(x)*p.z,sin(x)*p.y+cos(x)*p.z);float n=.05,f=192.;"
            +"gl_Position=vec4(p.x*uProjection/uAspect,p.y*uProjection,-(f+n)/(f-n)*p.z-2.*f*n/(f-n),-p.z);vColor=aColor;vUv=aUv;vWorld=aPosition;vBlock=aBlock;}";
    private static final String FRAGMENT="#version 150 core\n"
            +"in vec4 vColor;in vec2 vUv;in vec3 vWorld;flat in float vBlock;uniform sampler2D uItems,uBlocks;uniform vec3 uCamera;uniform float uMedium;out vec4 fragColor;"
            +"void main(){vec4 c=vColor*(vBlock>.5?texture(uBlocks,vUv):texture(uItems,vUv));if(c.a<.1)discard;fragColor=vec4(nhFog(c.rgb,vWorld,uCamera),c.a);}";
}
