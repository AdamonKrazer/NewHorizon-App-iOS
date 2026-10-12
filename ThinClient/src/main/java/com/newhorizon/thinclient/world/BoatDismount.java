package com.newhorizon.thinclient.world;

/** 1.20.1 Boat dismount candidates: facing shore, then the top of the boat over water. */
public final class BoatDismount {
    private BoatDismount() {}
    public static double place(WorldChunkStore world,double x,double y,double z,float yaw,double[] out) {
        double sin=-Math.sin(Math.toRadians(yaw)),cos=Math.cos(Math.toRadians(yaw));
        double distance=(1.375*Math.sqrt(2)+.6+1.0e-5)/2;
        double scale=distance/Math.max(Math.abs(sin),Math.abs(cos));
        double nx=x+sin*scale,nz=z+cos*scale;
        int bx=(int)Math.floor(nx),by=(int)Math.floor(y+.5625),bz=(int)Math.floor(nz);
        synchronized(world) {
            if((BlockStatePhysics.flags(world.blockStateAt(bx,by-1,bz))&BlockStatePhysics.WATER)==0) {
                for(int pose=0;pose<2;pose++) {
                    double height=pose==0?1.8:1.5;
                    for(int layer=0;layer<2;layer++) {
                        int cellY=by-layer;double floor=floor(world,bx,cellY,bz);
                        if(!Double.isFinite(floor)||floor>=1)continue;
                        double ny=cellY+floor;
                        if(!world.collidesBox(nx-.3,ny+1.0e-7,nz-.3,nx+.3,ny+height,nz+.3)) {
                            out[0]=nx;out[1]=ny;out[2]=nz;return height;
                        }
                    }
                }
            }
        }
        out[0]=x;out[1]=y+.5625;out[2]=z;return 1.8;
    }
    private static double floor(WorldChunkStore world,int x,int y,int z) {
        double top=top(world.blockStateAt(x,y,z));
        if(Double.isFinite(top))return top;
        double below=top(world.blockStateAt(x,y-1,z));
        return below>=1?below-1:Double.NEGATIVE_INFINITY;
    }
    private static double top(int state) {
        if(state<0)return Double.NEGATIVE_INFINITY;
        double top=Double.NEGATIVE_INFINITY;
        for(int i=0;i<BlockStatePhysics.collisionBoxCount(state);i++)
            top=Math.max(top,BlockStatePhysics.collisionCoordinate(state,i,4));
        return top;
    }
}
