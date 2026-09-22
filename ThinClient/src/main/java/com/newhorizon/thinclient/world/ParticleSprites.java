package com.newhorizon.thinclient.world;

import java.io.*;
import java.nio.charset.StandardCharsets;

/** Exact frame sequences from the official particle JSON files. */
public final class ParticleSprites {
    private static final int[][] FRAMES=load();
    private static int[][] load(){
        int[][] frames=new int[95][];
        try(BufferedReader in=new BufferedReader(new InputStreamReader(ParticleSprites.class.getResourceAsStream("/assets/newhorizon/particles/catalog.tsv"),StandardCharsets.UTF_8))){
            String line;while((line=in.readLine())!=null){String[] f=line.split("\t"),ids=f[2].split(",");int id=Integer.parseInt(f[0]);frames[id]=new int[ids.length];for(int i=0;i<ids.length;i++)frames[id][i]=Integer.parseInt(ids[i]);}
        }catch(IOException e){throw new IllegalStateException("Particle catalog",e);}return frames;
    }
    public static boolean supports(int type){return type>=0&&type<FRAMES.length&&FRAMES[type]!=null;}
    public static int frame(int type,float age,float life){if(!supports(type))return 0;int[] f=FRAMES[type];return f[Math.min(f.length-1,Math.max(0,(int)(age*f.length/Math.max(1,life))))];}
    private ParticleSprites(){}
}
