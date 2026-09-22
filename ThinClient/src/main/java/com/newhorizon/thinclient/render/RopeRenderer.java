package com.newhorizon.thinclient.render;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL33;
import java.nio.ByteBuffer;
import com.newhorizon.thinclient.world.*;
final class RopeRenderer implements AutoCloseable {
 private final Rope[] ropes=new Rope[RopeMesh.MAX_ROPES];
 private final ByteBuffer vertices=BufferUtils.createByteBuffer(RopeMesh.BYTES);
 private int program,vao,vbo;
 RopeRenderer(){for(int i=0;i<ropes.length;i++)ropes[i]=new Rope();}
 void initializeGl(){
  int vs=shader(GL33.GL_VERTEX_SHADER,VERTEX),fs=shader(GL33.GL_FRAGMENT_SHADER,FRAGMENT);
  program=GL33.glCreateProgram();GL33.glAttachShader(program,vs);GL33.glAttachShader(program,fs);
  GL33.glBindAttribLocation(program,0,"aPosition");GL33.glBindAttribLocation(program,1,"aColor");GL33.glLinkProgram(program);
  GL33.glDeleteShader(vs);GL33.glDeleteShader(fs);if(GL33.glGetProgrami(program,GL33.GL_LINK_STATUS)==0)throw new IllegalStateException(GL33.glGetProgramInfoLog(program));
  vao=GL33.glGenVertexArrays();vbo=GL33.glGenBuffers();GL33.glBindVertexArray(vao);GL33.glBindBuffer(GL33.GL_ARRAY_BUFFER,vbo);
  GL33.glBufferData(GL33.GL_ARRAY_BUFFER,(long)vertices.capacity(),GL33.GL_STREAM_DRAW);
  GL33.glEnableVertexAttribArray(0);GL33.glVertexAttribPointer(0,3,GL33.GL_FLOAT,false,16,0L);
  GL33.glEnableVertexAttribArray(1);GL33.glVertexAttribPointer(1,4,GL33.GL_UNSIGNED_BYTE,true,16,12L);
 }
 void draw(EntityTracker tracker,float x,float y,float z,float yaw,float pitch,float aspect,float projection,float medium){
  int count=tracker.ropes(ropes,System.nanoTime(),x,y,z),n=RopeMesh.mesh(vertices,ropes,count,x,y,z);if(n==0)return;
  vertices.flip();ScreenEffects.bind(program);
  GL33.glUniform3f(GL33.glGetUniformLocation(program,"uCamera"),x,y,z);GL33.glUniform2f(GL33.glGetUniformLocation(program,"uRotation"),yaw,pitch);
  GL33.glUniform1f(GL33.glGetUniformLocation(program,"uAspect"),aspect);GL33.glUniform1f(GL33.glGetUniformLocation(program,"uProjection"),projection);GL33.glUniform1f(GL33.glGetUniformLocation(program,"uMedium"),medium);
  GL33.glBindVertexArray(vao);GL33.glBindBuffer(GL33.GL_ARRAY_BUFFER,vbo);GL33.glBufferSubData(GL33.GL_ARRAY_BUFFER,0,vertices);
  boolean cull=GL33.glIsEnabled(GL33.GL_CULL_FACE);GL33.glDisable(GL33.GL_CULL_FACE);GL33.glDrawArrays(GL33.GL_TRIANGLES,0,n);if(cull)GL33.glEnable(GL33.GL_CULL_FACE);
 }
 private static int shader(int type,String code){int id=GL33.glCreateShader(type);GL33.glShaderSource(id,ScreenEffects.shader(type,code));GL33.glCompileShader(id);if(GL33.glGetShaderi(id,GL33.GL_COMPILE_STATUS)==0)throw new IllegalStateException(GL33.glGetShaderInfoLog(id));return id;}
 public void close(){if(program!=0)ScreenEffects.deleteProgram(program);if(vao!=0)GL33.glDeleteVertexArrays(vao);if(vbo!=0)GL33.glDeleteBuffers(vbo);program=vao=vbo=0;}
    private static final String VERTEX="#version 150 core\n"
            +"in vec3 aPosition; in vec4 aColor; out vec4 vColor; out vec3 vWorld;\n"
            +"uniform vec3 uCamera; uniform vec2 uRotation; uniform float uAspect,uProjection;\n"
            +"void main(){ vec3 p=aPosition-uCamera; float y=radians(-uRotation.x);"
            +"p=vec3(cos(y)*p.x-sin(y)*p.z,p.y,sin(y)*p.x+cos(y)*p.z); p.x=-p.x;p.z=-p.z;"
            +"float x=radians(uRotation.y);p=vec3(p.x,cos(x)*p.y-sin(x)*p.z,sin(x)*p.y+cos(x)*p.z);"
            +"float n=.05,f=192.;gl_Position=vec4(p.x*uProjection/uAspect,p.y*uProjection,-(f+n)/(f-n)*p.z-2.*f*n/(f-n),-p.z);"
            +"vColor=aColor;vWorld=aPosition;}";
    private static final String FRAGMENT="#version 150 core\n"
            +"in vec4 vColor;in vec3 vWorld;out vec4 fragColor;uniform vec3 uCamera;uniform float uMedium;"
            +"void main(){fragColor=vec4(nhFog(vColor.rgb*nhLight(nhEyeLight),vWorld,uCamera),1.);}";
}
