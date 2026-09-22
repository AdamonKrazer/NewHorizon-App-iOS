package com.newhorizon.thinclient.world;

import com.newhorizon.thinclient.audio.BiomeSoundRegistry;

/** Small scalar equivalent of the vanilla sky, fog and lightmap calculations. */
public final class AtmosphereState {
    public final float[] sky=new float[3],fog=new float[3],sunset=new float[4];
    public float daylight=1,skyLight=1,skyTint=1,ambient,gamma=.5f;
    public float fogStart=48,fogEnd=64,sunDirection=1;
    public boolean sphericalFog,end,nether;

    public static double sunAngle(long time) {
        double fraction=Math.floorMod(time,24000)/24000.0-.25;
        fraction-=Math.floor(fraction);
        return (fraction*2+.5-Math.cos(fraction*Math.PI)*.5)/3*Math.PI*2;
    }

    public void sample(EnvironmentState.Snapshot state,String dimension,BiomeSoundRegistry.Biome biome,
                       int medium,int viewChunks,float yaw) {
        end="minecraft:the_end".equals(dimension);nether="minecraft:the_nether".equals(dimension);
        double angle=sunAngle(state.dayTime);
        float height=(float)Math.cos(angle);
        daylight=clamp(height*2+.5f);
        float sun=1-clamp(1-(height*2+.2f));
        sun*=1-state.rain*5/16f;sun*=1-state.thunder*5/16f;
        skyLight=sun*.95f+.05f;skyTint=sun*.65f+.35f;
        ambient=nether?.1f:0;
        color(biome==null?0x78a7ff:biome.skyColor,sky);
        color(biome==null?0xc0d8ff:biome.fogColor,fog);
        for(int i=0;i<3;i++)sky[i]*=daylight;
        weather(sky,state.rain,state.thunder);
        fog[0]*=daylight*.94f+.06f;fog[1]*=daylight*.94f+.06f;fog[2]*=daylight*.91f+.09f;
        sunset[3]=0;
        sunDirection=Math.sin(angle)>0?-1:1;
        if(height>=-.4f&&height<=.4f&&!nether&&!end) {
            float t=height/.4f*.5f+.5f;
            float alpha=1-(1-(float)Math.sin(t*Math.PI))*.99f;
            sunset[0]=t*.3f+.7f;sunset[1]=t*t*.7f+.2f;sunset[2]=.2f;
            sunset[3]=alpha*alpha*(1-state.rain);
            float facing=Math.max(0,-(float)Math.sin(Math.toRadians(yaw))*sunDirection)*sunset[3];
            for(int i=0;i<3;i++)fog[i]=mix(fog[i],sunset[i],facing);
        }
        float skyMix=1-(float)Math.pow(.25+.75*Math.max(2,Math.min(4,viewChunks))/32,.25);
        for(int i=0;i<3;i++)fog[i]=mix(fog[i],sky[i],skyMix);
        fog[0]*=1-state.rain*.5f;fog[1]*=1-state.rain*.5f;fog[2]*=1-state.rain*.4f;
        for(int i=0;i<3;i++)fog[i]*=1-state.thunder*.5f;
        fogEnd=Math.max(2,Math.min(4,viewChunks))*16;fogStart=fogEnd*.75f;sphericalFog=false;
        if(nether){color(biome==null?0x330808:biome.fogColor,fog);fogStart=fogEnd*.05f;fogEnd*=.5f;}
        if(end){color(0x181318,fog);for(int i=0;i<3;i++)fog[i]*=.15f;}
        if(medium==1){color(biome==null?0x050533:biome.waterFogColor,fog);fogStart=-8;fogEnd=Math.min(96,fogEnd);sphericalFog=true;}
        if(medium==2){color(0x991900,fog);fogStart=.25f;fogEnd=1;sphericalFog=true;}
    }

    /** LightTexture brightness ramp and warm block light, including the two 4% ambient mixes. */
    public void light(float skyLevel,float blockLevel,float[] out) {
        float s=nether||end?0:mix(brightness(skyLevel),1,ambient)*skyLight;
        float b=mix(brightness(blockLevel),1,ambient)*1.5f;
        out[0]=b;out[1]=b*((b*.6f+.4f)*.6f+.4f);out[2]=b*(b*b*.6f+.4f);
        for(int i=0;i<3;i++) {
            float value=end?mix(out[i],i==1?1.12f:i==0?.99f:1,.25f):mix(out[i]+s*(i==2?1:skyTint),.75f,.04f);
            value=clamp(value);float inverse=1-value;
            value=mix(value,1-inverse*inverse*inverse*inverse,gamma);
            out[i]=clamp(mix(value,.75f,.04f));
        }
    }
    private static void weather(float[] c,float rain,float thunder) {
        float grey=(c[0]*.3f+c[1]*.59f+c[2]*.11f)*.6f;
        for(int i=0;i<3;i++)c[i]=mix(c[i],grey,rain*.75f);
        grey=(c[0]*.3f+c[1]*.59f+c[2]*.11f)*.2f;
        for(int i=0;i<3;i++)c[i]=mix(c[i],grey,thunder*.75f);
    }
    public static float brightness(float level){float v=clamp(level/15);return v/(4-3*v);}
    public static float clamp(float value){return Math.max(0,Math.min(1,value));}
    private static float mix(float a,float b,float t){return a+(b-a)*t;}
    private static void color(int rgb,float[] out){out[0]=(rgb>>16&255)/255f;out[1]=(rgb>>8&255)/255f;out[2]=(rgb&255)/255f;}
}
