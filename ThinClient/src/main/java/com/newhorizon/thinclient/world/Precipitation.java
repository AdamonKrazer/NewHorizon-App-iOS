package com.newhorizon.thinclient.world;

import com.newhorizon.thinclient.audio.BiomeSoundRegistry;
import com.newhorizon.thinclient.audio.VanillaSoundCatalog;

/** Terrain contact and world-anchored precipitation geometry, independent of GL. */
public final class Precipitation {
    public static final int RADIUS=10, COLUMNS=(RADIUS*2+1)*(RADIUS*2+1);
    public static final class Column {
        public float x,z,bottom,top,rightX,rightZ,alpha,scroll,u,red,green,blue;
        public int kind,surface;
    }
    public static int kind(BiomeSoundRegistry.Biome biome) {
        return biome==null||!biome.precipitation?0:biome.temperature<.15f?2:1;
    }
    public static boolean column(Column out,int x,int z,float cameraX,float cameraY,float cameraZ,
                                 float yaw,int surface,boolean snow,float rain,double ticks) {
        if(surface==Integer.MIN_VALUE||rain<=0)return false;
        out.bottom=Math.max((int)Math.floor(cameraY)-RADIUS,surface);
        out.top=Math.max((int)Math.floor(cameraY)+RADIUS,surface);
        if(out.bottom>=out.top)return false;
        out.x=x+.5f;out.z=z+.5f;
        double dx=out.x-cameraX,dz=out.z-cameraZ,distance=Math.sqrt(dx*dx+dz*dz);
        if(distance<.001){out.rightX=(float)Math.cos(Math.toRadians(yaw))*.5f;out.rightZ=(float)Math.sin(Math.toRadians(yaw))*.5f;}
        else {out.rightX=(float)(-dz/distance)*.5f;out.rightZ=(float)(dx/distance)*.5f;}
        float fade=(float)(1-distance*distance/(RADIUS*RADIUS));
        out.alpha=Math.max(0,snow?.3f+.5f*fade:.5f+.5f*fade)*Math.min(1,rain);
        int seed=(x*x*3121+x*45238971)^(z*z*418711+z*13761);
        float random=(seed>>>8&65535)/65535f;
        double scroll=-(ticks+(seed&31))*(snow?.01:(3+random)/32);
        out.scroll=(float)(scroll-Math.floor(scroll)); // preserve sub-frame UV precision on long sessions
        out.u=snow?(float)(Math.sin(ticks*.01+random*6.28)*.3):0;
        return true;
    }
    /** Collision shape height at the actual impact point, including waterlogged blocks. */
    public static double impactHeight(int state,double x,double z) {
        double height=(BlockStatePhysics.flags(state)&(BlockStatePhysics.WATER|BlockStatePhysics.LAVA))!=0
                ?BlockStatePhysics.fluidAmount(state)/9.0:0;
        for(int box=0;box<BlockStatePhysics.collisionBoxCount(state);box++) {
            if(x>=BlockStatePhysics.collisionCoordinate(state,box,0)&&x<=BlockStatePhysics.collisionCoordinate(state,box,3)
                    &&z>=BlockStatePhysics.collisionCoordinate(state,box,2)&&z<=BlockStatePhysics.collisionCoordinate(state,box,5))
                height=Math.max(height,BlockStatePhysics.collisionCoordinate(state,box,4));
        }
        return height;
    }
    public static boolean hot(int state) {
        if((BlockStatePhysics.flags(state)&BlockStatePhysics.LAVA)!=0)return true;
        String name=VanillaSoundCatalog.load().blockName(state);
        return "minecraft:magma_block".equals(name)
                ||(("minecraft:campfire".equals(name)||"minecraft:soul_campfire".equals(name))
                &&(AmbientParticleSources.source(state)&15)!=0);
    }
    private Precipitation(){}
}
