package com.newhorizon.thinclient.render;

import com.newhorizon.thinclient.world.PlayerEffects;
import org.lwjgl.opengl.GL33;

/** Shared projection/fog modifiers. No framebuffer copy or additional world texture. */
final class ScreenEffects {
    private static final int[] programs=new int[32],uniforms=new int[32];
    private static int count;
    private static float nausea,angle,blindness,darkness,nightVision;
    private static long previous;
    private static float hurtRoll;
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
    static boolean obscuresSky() { return blindness>0 || darkness>.1f; }
    /** OpenGL may reuse a deleted program name; cached locations belong to its old link. */
    static void deleteProgram(int program) {
        SceneAtmosphere.forget(program);
        for(int i=0;i<count;i++) if(programs[i]==program) {
            int last=--count;programs[i]=programs[last];uniforms[i]=uniforms[last];
            programs[last]=uniforms[last]=0;break;
        }
        GL33.glDeleteProgram(program);
    }
    static void bind(int program) {
        GL33.glUseProgram(program);
        SceneAtmosphere.bind(program);
        int index=0; while(index<count && programs[index]!=program) index++;
        if(index==count) {
            if(count==programs.length) throw new IllegalStateException("Effect shader capacity exceeded");
            programs[count]=program; uniforms[count++]=GL33.glGetUniformLocation(program,"nhEffect");
        }
        GL33.glUniform4f(uniforms[index],nausea,angle,blindness,darkness);
        GL33.glUniform1f(GL33.glGetUniformLocation(program,"nhNightVision"),nightVision);
        GL33.glUniform1f(GL33.glGetUniformLocation(program,"nhHurtRoll"),hurtRoll);
    }
    static String shader(int type,String source) {
        if(type==GL33.GL_FRAGMENT_SHADER)source=source.replace("#version 150 core\n","#version 150 core\n"+SceneAtmosphere.GLSL);
        String renamed=source.replaceFirst("void\\s+main\\s*\\(\\s*\\)","void nhOriginalMain()");
        if(type==GL33.GL_VERTEX_SHADER) return renamed
            +"\nuniform vec4 nhEffect; uniform float nhHurtRoll; out float nhDistance;\n"
            +"void main(){nhOriginalMain();nhDistance=gl_Position.w;float n=nhEffect.x*nhEffect.x;"
            +"float k=5.0/(n*n+5.0)-n*.04;float c=cos(nhEffect.y),s=sin(nhEffect.y);"
            +"vec2 p=mat2(c,-s,s,c)*gl_Position.xy;p.x/=k;gl_Position.xy=mat2(c,s,-s,c)*p;"
            +"c=cos(nhHurtRoll);s=sin(nhHurtRoll);gl_Position.xy=mat2(c,-s,s,c)*gl_Position.xy;}\n";
        return renamed+"\nuniform vec4 nhEffect;uniform float nhNightVision;in float nhDistance;\n"
            +"void main(){nhOriginalMain();float b=max(max(fragColor.r,fragColor.g),fragColor.b);"
            +"if(b>0.0)fragColor.rgb=mix(fragColor.rgb,fragColor.rgb/b,nhNightVision);"
            +"float fog=nhEffect.z*smoothstep(1.25,5.0,nhDistance);"
            +"fog=max(fog,nhEffect.w*smoothstep(2.0,15.0,nhDistance));fragColor.rgb*=1.0-fog;}\n";
    }
    static void reset() { count=0; previous=0; nausea=0; SceneAtmosphere.reset(); }
}
