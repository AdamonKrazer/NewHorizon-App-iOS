package com.newhorizon.thinclient.render;

import com.newhorizon.thinclient.world.AtmosphereState;
import com.newhorizon.thinclient.world.WorldChunkStore;
import org.lwjgl.opengl.GL33;

/** Shared fog/light uniforms; no extra texture, framebuffer or world copy. */
final class SceneAtmosphere {
    static final int VIEW_CHUNKS=Math.max(2,Math.min(4,Integer.getInteger("newhorizon.thin.viewDistance",4)));
    static AtmosphereState state=new AtmosphereState();
    private static WorldChunkStore world;
    private static float eyeSky=15,eyeBlock;
    private static final int[] ids=new int[32];
    private static final int[][] locations=new int[32][6];
    private static int count;
    static void eyeAttribute(int location){GL33.glDisableVertexAttribArray(location);GL33.glVertexAttrib2f(location,eyeSky,eyeBlock);}
    static void update(AtmosphereState value,WorldChunkStore chunks,float x,float y,float z) {
        state=value;world=chunks;
        eyeSky=chunks.lightAt(true,(int)Math.floor(x),(int)Math.floor(y),(int)Math.floor(z));
        eyeBlock=chunks.lightAt(false,(int)Math.floor(x),(int)Math.floor(y),(int)Math.floor(z));
        if(value.nether||value.end)eyeSky=0;
    }
    static void light(float x,float y,float z,float[] out) {
        if(world==null){out[0]=out[1]=out[2]=1;return;}
        int bx=(int)Math.floor(x),by=(int)Math.floor(y),bz=(int)Math.floor(z);
        state.light(state.nether||state.end?0:world.lightAt(true,bx,by,bz),world.lightAt(false,bx,by,bz),out);
    }
    static void bind(int program) {
        int i=0;while(i<count&&ids[i]!=program)i++;
        if(i==count){if(count==ids.length)throw new IllegalStateException("Atmosphere shader capacity");ids[count++]=program;
            String[] names={"nhFogColor","nhFogRange","nhLighting","nhEnd","nhEyeLight","nhNoSky"};
            for(int n=0;n<names.length;n++)locations[i][n]=GL33.glGetUniformLocation(program,names[n]);}
        int[] u=locations[i];
        GL33.glUniform3f(u[0],state.fog[0],state.fog[1],state.fog[2]);
        GL33.glUniform3f(u[1],state.fogStart,state.fogEnd,state.sphericalFog?1:0);
        GL33.glUniform4f(u[2],state.skyLight,state.skyTint,state.ambient,state.gamma);
        GL33.glUniform1f(u[3],state.end?1:0);GL33.glUniform2f(u[4],eyeSky,eyeBlock);
        GL33.glUniform1f(u[5],state.end||state.nether?1:0);
    }
    static void forget(int program){for(int i=0;i<count;i++)if(ids[i]==program){int last=--count;ids[i]=ids[last];System.arraycopy(locations[last],0,locations[i],0,6);break;}}
    static void reset(){count=0;world=null;state=new AtmosphereState();eyeSky=15;eyeBlock=0;}
    static final String GLSL=
        "uniform vec3 nhFogColor,nhFogRange;uniform vec4 nhLighting;uniform float nhEnd,nhNoSky;uniform vec2 nhEyeLight;\n"
        +"vec3 nhLight(vec2 levels){vec2 v=clamp(levels/15.,0.,1.);v=mix(v/(4.-3.*v),vec2(1.),nhLighting.z);"
        +"float s=v.x*nhLighting.x*(1.-nhNoSky),b=v.y*1.5;vec3 c=vec3(b,b*((b*.6+.4)*.6+.4),b*(b*b*.6+.4));"
        +"if(nhEnd>.5)c=mix(c,vec3(.99,1.12,1.),.25);else c=mix(c+vec3(nhLighting.y,nhLighting.y,1.)*s,vec3(.75),.04);"
        +"c=clamp(c,0.,1.);c=mix(c,1.-pow(1.-c,vec3(4.)),nhLighting.w);return clamp(mix(c,vec3(.75),.04),0.,1.);}\n"
        +"vec3 nhFog(vec3 color,vec3 world,vec3 camera){vec3 p=world-camera;float d=nhFogRange.z>.5?length(p):max(length(p.xz),abs(p.y));"
        +"return mix(color,nhFogColor,smoothstep(nhFogRange.x,nhFogRange.y,d));}\n";
}
