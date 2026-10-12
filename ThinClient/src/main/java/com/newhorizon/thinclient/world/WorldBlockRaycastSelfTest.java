package com.newhorizon.thinclient.world;

import java.util.HashMap;
import java.util.Map;

public final class WorldBlockRaycastSelfTest {
    public static final class Grid implements WorldBlockRaycast.Blocks {
        private final Map<Long,Integer> cells=new HashMap<>();
        public Grid put(int x,int y,int z,int state){cells.put(key(x,y,z),state);return this;}
        public int blockStateAt(int x,int y,int z){Integer value=cells.get(key(x,y,z));return value==null?0:value;}
        private long key(int x,int y,int z){return ((long)x&0x3ffffff)<<38|((long)z&0x3ffffff)<<12|((long)y&0xfff);}
    }
    public static void main(String[] args){run();}
    public static void run() {
        WorldBlockRaycast.Hit h=new WorldBlockRaycast.Hit();
        Grid g=new Grid().put(1,0,0,1);
        check(WorldBlockRaycast.trace(g,.01,.5,.001,1,0,1,5,h)&&h.blockX==1&&h.blockZ==0&&h.face==4,
                "DDA visits grazing cell occupied for less than old 0.04-block ray step");
        g=new Grid().put(0,0,0,2355).put(0,0,2,1);
        check(WorldBlockRaycast.trace(g,.1,.5,-1,0,0,1,5,h)&&h.blockZ==2,"miss torch outline and select block behind");
        check(WorldBlockRaycast.trace(g,.5,.5,-1,0,0,1,5,h)&&h.blockZ==0&&h.face==2,"torch outline blocks farther selection");
        g=new Grid().put(0,0,0,18309);
        check(WorldBlockRaycast.trace(g,2,1.05,.7,-1,0,0,4,h)&&h.blockY==0&&h.face==5&&close(h.distance,1),"lectern above its owning cell");
        g=new Grid().put(0,0,0,2025);
        check(WorldBlockRaycast.trace(g,.5,.5,2,0,0,-1,1,h)&&h.blockZ==0&&h.face==3&&close(h.distance,.75),"piston rod outside its owning cell");
        g=new Grid().put(0,0,0,12357);
        check(WorldBlockRaycast.trace(g,.5,-.02,-1,0,0,1,3,h)&&h.blockY==0&&h.face==2,"pitcher root below its owning cell");
        g=new Grid().put(-2,0,-1,1);
        check(WorldBlockRaycast.trace(g,-1,.5,-.5,-1,0,0,5,h)&&h.blockX==-2&&h.face==5&&close(h.distance,0),"negative ray starts on exact cell boundary");
        g=new Grid().put(0,0,0,1);
        check(WorldBlockRaycast.trace(g,.5,.5,.5,1,0,0,5,h)&&h.inside&&h.distance==0,"start inside shape");
        g=new Grid().put(63,0,0,1);
        check(WorldBlockRaycast.trace(g,.5,.5,.5,1,0,0,64,h)&&h.blockX==63&&close(h.distance,62.5),"long bounded ray");
        check(!WorldBlockRaycast.trace(g,.5,.5,.5,1,0,0,62,h),"range limit enforced");
        check(!WorldBlockRaycast.trace(g,.5,.5,.5,0,0,0,5,h)&&!WorldBlockRaycast.trace(g,.5,.5,.5,1,0,0,65,h),"zero direction and excessive range rejected");
        check(!WorldBlockRaycast.trace((x,y,z)->-1,0,0,0,1,0,0,5,h),"unloaded cells do not become cubes");
        System.out.println("World ray tests passed: grazing DDA, nearest hit, empty torch space, lectern/piston/crop protrusions, signed boundary, inside and reach");
    }
    private static boolean close(double a,double b){return Math.abs(a-b)<1e-8;}
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
}