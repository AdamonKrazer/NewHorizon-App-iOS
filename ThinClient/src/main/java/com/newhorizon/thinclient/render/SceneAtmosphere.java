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
    private static final float[][] sent=new float[32][14];
    private static final boolean[] initialized=new boolean[32];
    static long uniformUploads;
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
    static void debrisLight(float x,float y,float z,float[] out) {
        if(world==null){light(x,y,z,out);return;}
        int bx=(int)Math.floor(x),by=(int)Math.floor(y),bz=(int)Math.floor(z);
        int sky=world.lightAt(true,bx,by,bz),block=world.lightAt(false,bx,by,bz);
        // Fragments can still occupy the opaque source cell, or an air cell whose
        // light update has not arrived yet. Use its exposed neighbors in that case.
        if((sky==0&&block==0)||com.newhorizon.thinclient.world.BlockStatePhysics.fullyOccludesUnitCube(world.blockStateAt(bx,by,bz))) {
            for(int face=0;face<6;face++) {
                int nx=bx+(face==4?-1:face==5?1:0),ny=by+(face==0?-1:face==1?1:0),nz=bz+(face==2?-1:face==3?1:0);
                if(com.newhorizon.thinclient.world.BlockStatePhysics.fullyOccludesUnitCube(world.blockStateAt(nx,ny,nz)))continue;
                sky=Math.max(sky,world.lightAt(true,nx,ny,nz));block=Math.max(block,world.lightAt(false,nx,ny,nz));
            }
        }
        state.light(state.nether||state.end?0:sky,block,out);
    }
    static void bind(int program) {
        int i=0;while(i<count&&ids[i]!=program)i++;
        if(i==count){if(count==ids.length)throw new IllegalStateException("Atmosphere shader capacity");ids[count]=program;initialized[count++]=false;
            String[] names={"nhFogColor","nhFogRange","nhLighting","nhEnd","nhEyeLight","nhNoSky"};
            for(int n=0;n<names.length;n++)locations[i][n]=GL33.glGetUniformLocation(program,names[n]);}
        int[] u=locations[i];float[] last=sent[i];boolean fresh=!initialized[i];
        float spherical=state.sphericalFog?1:0,end=state.end?1:0,noSky=state.end||state.nether?1:0;
        if(u[0]>=0&&(fresh||last[0]!=state.fog[0]||last[1]!=state.fog[1]||last[2]!=state.fog[2])){GL33.glUniform3f(u[0],state.fog[0],state.fog[1],state.fog[2]);uniformUploads++;}
        if(u[1]>=0&&(fresh||last[3]!=state.fogStart||last[4]!=state.fogEnd||last[5]!=spherical)){GL33.glUniform3f(u[1],state.fogStart,state.fogEnd,spherical);uniformUploads++;}
        if(u[2]>=0&&(fresh||last[6]!=state.skyLight||last[7]!=state.skyTint||last[8]!=state.ambient||last[9]!=state.gamma)){GL33.glUniform4f(u[2],state.skyLight,state.skyTint,state.ambient,state.gamma);uniformUploads++;}
        if(u[3]>=0&&(fresh||last[10]!=end)){GL33.glUniform1f(u[3],end);uniformUploads++;}
        if(u[4]>=0&&(fresh||last[11]!=eyeSky||last[12]!=eyeBlock)){GL33.glUniform2f(u[4],eyeSky,eyeBlock);uniformUploads++;}
        if(u[5]>=0&&(fresh||last[13]!=noSky)){GL33.glUniform1f(u[5],noSky);uniformUploads++;}
        last[0]=state.fog[0];last[1]=state.fog[1];last[2]=state.fog[2];last[3]=state.fogStart;last[4]=state.fogEnd;last[5]=spherical;
        last[6]=state.skyLight;last[7]=state.skyTint;last[8]=state.ambient;last[9]=state.gamma;last[10]=end;last[11]=eyeSky;last[12]=eyeBlock;last[13]=noSky;initialized[i]=true;
    }
    static void forget(int program){for(int i=0;i<count;i++)if(ids[i]==program){int last=--count;ids[i]=ids[last];System.arraycopy(locations[last],0,locations[i],0,6);System.arraycopy(sent[last],0,sent[i],0,14);initialized[i]=initialized[last];initialized[last]=false;break;}}
    static void reset(){count=0;uniformUploads=0;world=null;state=new AtmosphereState();eyeSky=15;eyeBlock=0;}
    static final String GLSL=
        "uniform vec3 nhFogColor,nhFogRange;uniform vec4 nhLighting;uniform float nhEnd,nhNoSky;uniform vec2 nhEyeLight;\n"
        +"vec3 nhLight(vec2 levels){vec2 v=clamp(levels/15.,0.,1.);v=mix(v/(4.-3.*v),vec2(1.),nhLighting.z);"
        +"float s=v.x*nhLighting.x*(1.-nhNoSky),b=v.y*1.5;vec3 c=vec3(b,b*((b*.6+.4)*.6+.4),b*(b*b*.6+.4));"
        +"if(nhEnd>.5)c=mix(c,vec3(.99,1.12,1.),.25);else c=mix(c+vec3(nhLighting.y,nhLighting.y,1.)*s,vec3(.75),.04);"
        +"c=clamp(c,0.,1.);c=mix(c,1.-pow(1.-c,vec3(4.)),nhLighting.w);return clamp(mix(c,vec3(.75),.04),0.,1.);}\n"
        +"vec3 nhFog(vec3 color,vec3 world,vec3 camera){vec3 p=world-camera;float d=nhFogRange.z>.5?length(p):max(length(p.xz),abs(p.y));"
        +"return mix(color,nhFogColor,smoothstep(nhFogRange.x,nhFogRange.y,d));}\n";
}
