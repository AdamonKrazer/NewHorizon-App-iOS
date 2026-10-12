package com.newhorizon.thinclient.render;

import com.newhorizon.thinclient.world.PlayerEffects;
import org.lwjgl.opengl.GL33;

/** Shared projection/fog modifiers. No framebuffer copy or additional world texture. */
final class ScreenEffects {
    private static final int[] programs=new int[32],uniforms=new int[32];
    private static final int[] nightVisionUniforms=new int[32],hurtRollUniforms=new int[32];
    private static int count;
    private static final float[][] sent=new float[32][6];
    private static final boolean[] initialized=new boolean[32];
    static long uniformUploads;
    private static float nausea,angle,blindness,darkness,nightVision;
    private static long previous;
    private static float hurtRoll, portal;
    static void portal(float value) { portal=value; }
    static void hurt(float degrees) { hurtRoll=(float)Math.toRadians(degrees); }
    private ScreenEffects() { }
    static void update(PlayerEffects effects,long now) {
        float seconds=previous==0?0:Math.min(.1f,(now-previous)/1_000_000_000f); previous=now;
        nausea=Math.max(0,Math.min(1,nausea+(effects.level(PlayerEffects.NAUSEA)>0?.133333f:-.8f)*seconds));
        angle=(float)((now/1_000_000_000.0*2.443461)% (2*Math.PI));
        blindness=effects.level(PlayerEffects.BLINDNESS)>0?1:0;
        darkness=effects.level(PlayerEffects.DARKNESS)>0?.65f+.35f*(float)Math.cos(now/1_000_000_000.0*Math.PI):0;
        nightVision=effects.level(PlayerEffects.NIGHT_VISION)>0?1:0;
    }
    static boolean distortsProjection(){return nausea!=0||portal!=0||hurtRoll!=0;}
    static boolean obscuresSky() { return blindness>0 || darkness>.1f; }
    /** OpenGL may reuse a deleted program name; cached locations belong to its old link. */
    static void deleteProgram(int program) {
        BlockAnimations.forget(program);
        SceneAtmosphere.forget(program);
        for(int i=0;i<count;i++) if(programs[i]==program) {
            int last=--count;programs[i]=programs[last];uniforms[i]=uniforms[last];
            nightVisionUniforms[i]=nightVisionUniforms[last];hurtRollUniforms[i]=hurtRollUniforms[last];
            System.arraycopy(sent[last],0,sent[i],0,6);initialized[i]=initialized[last];
            programs[last]=uniforms[last]=0;initialized[last]=false;break;
        }
        GL33.glDeleteProgram(program);
    }
    static void bind(int program) {
        GL33.glUseProgram(program);
        SceneAtmosphere.bind(program);
        BlockAnimations.bind(program);
        int index=0; while(index<count && programs[index]!=program) index++;
        if(index==count) {
            if(count==programs.length) throw new IllegalStateException("Effect shader capacity exceeded");
            programs[count]=program; uniforms[count]=GL33.glGetUniformLocation(program,"nhEffect");
            nightVisionUniforms[count]=GL33.glGetUniformLocation(program,"nhNightVision");
            hurtRollUniforms[count]=GL33.glGetUniformLocation(program,"nhHurtRoll");initialized[count]=false;count++;
        }
        float[] last=sent[index];float distortion=Math.max(nausea,portal);boolean fresh=!initialized[index];
        if(uniforms[index]>=0&&(fresh||last[0]!=distortion||last[1]!=angle||last[2]!=blindness||last[3]!=darkness)) {
            GL33.glUniform4f(uniforms[index],distortion,angle,blindness,darkness);uniformUploads++;
        }
        if(nightVisionUniforms[index]>=0&&(fresh||last[4]!=nightVision)){GL33.glUniform1f(nightVisionUniforms[index],nightVision);uniformUploads++;}
        if(hurtRollUniforms[index]>=0&&(fresh||last[5]!=hurtRoll)){GL33.glUniform1f(hurtRollUniforms[index],hurtRoll);uniformUploads++;}
        last[0]=distortion;last[1]=angle;last[2]=blindness;last[3]=darkness;last[4]=nightVision;last[5]=hurtRoll;initialized[index]=true;
    }
    /** Conservative billboard clipping in the same clip space as the vertex shader. */
    static boolean clipRectVisible(double minX,double minY,double maxX,double maxY,double depth) {
        double margin=.01+Math.abs(depth)*.0001;
        if(depth<.05-margin||depth>192+margin)return false;
        double n=Math.max(nausea,portal);n*=n;
        double k=5.0/(n*n+5.0)-n*.04,c=Math.cos(angle),s=Math.sin(angle);
        double hc=Math.cos(hurtRoll),hs=Math.sin(hurtRoll);
        int outside=15;
        for(int i=0;i<4;i++) {
            double x=(i&1)==0?minX:maxX,y=(i&2)==0?minY:maxY;
            double px=(c*x+s*y)/k,py=-s*x+c*y;
            x=c*px-s*py;y=s*px+c*py;
            px=hc*x+hs*y;py=-hs*x+hc*y;
            int bits=0;if(px < -depth-margin)bits|=1;if(px > depth+margin)bits|=2;
            if(py < -depth-margin)bits|=4;if(py > depth+margin)bits|=8;
            outside&=bits;
        }
        return outside==0;
    }
    static String shader(int type,String source) {
        source=BlockAnimations.shader(type,source);
        if(type==GL33.GL_FRAGMENT_SHADER)source=source.replace("#version 150 core\n","#version 150 core\n"+SceneAtmosphere.GLSL);
        String overlayReturn=source.contains("uniform int uOverlay;")?"if(uOverlay!=0)return;":"";
        String renamed=source.replaceFirst("void\\s+main\\s*\\(\\s*\\)","void nhOriginalMain()");
        if(type==GL33.GL_VERTEX_SHADER) return renamed
            +"\nuniform vec4 nhEffect; uniform float nhHurtRoll; out float nhDistance;\n"
            +"void main(){nhOriginalMain();nhDistance=gl_Position.w;"+overlayReturn+"float n=nhEffect.x*nhEffect.x;"
            +"float k=5.0/(n*n+5.0)-n*.04;float c=cos(nhEffect.y),s=sin(nhEffect.y);"
            +"vec2 p=mat2(c,-s,s,c)*gl_Position.xy;p.x/=k;gl_Position.xy=mat2(c,s,-s,c)*p;"
            +"c=cos(nhHurtRoll);s=sin(nhHurtRoll);gl_Position.xy=mat2(c,-s,s,c)*gl_Position.xy;}\n";
        if(source.contains("uniform float uMedium,uMaterialPass,uCrumbling;"))overlayReturn+="if(uCrumbling>.5)return;";
        return renamed+"\nuniform vec4 nhEffect;uniform float nhNightVision;in float nhDistance;\n"
            +"void main(){nhOriginalMain();"+overlayReturn+"float b=max(max(fragColor.r,fragColor.g),fragColor.b);"
            +"if(b>0.0)fragColor.rgb=mix(fragColor.rgb,fragColor.rgb/b,nhNightVision);"
            +"float fog=nhEffect.z*smoothstep(1.25,5.0,nhDistance);"
            +"fog=max(fog,nhEffect.w*smoothstep(2.0,15.0,nhDistance));fragColor.rgb*=1.0-fog;}\n";
    }
    static void reset() { count=0;uniformUploads=0; previous=0; nausea=portal=0; SceneAtmosphere.reset(); }
}
