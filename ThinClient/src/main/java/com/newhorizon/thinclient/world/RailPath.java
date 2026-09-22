package com.newhorizon.thinclient.world;

/** Render-only AbstractMinecart.getPos/getPosOffs projection. Server coordinates stay intact. */
public final class RailPath {
    private static final int[][] ENDS={{0,0,-1,0,0,1},{-1,0,0,1,0,0},{-1,-1,0,1,0,0},
            {-1,0,0,1,-1,0},{0,0,-1,0,-1,1},{0,-1,-1,0,0,1},
            {0,0,1,1,0,0},{0,0,1,-1,0,0},{0,0,-1,-1,0,0},{0,0,-1,1,0,0}};
    private RailPath() {}
    public static boolean project(WorldChunkStore world,double x,double y,double z,double[] out) {
        if(world==null)return false;
        int bx=(int)Math.floor(x),by=(int)Math.floor(y),bz=(int)Math.floor(z);
        if(RailState.isRail(world.blockStateAt(bx,by-1,bz)))by--;
        int state=world.blockStateAt(bx,by,bz);if(!RailState.isRail(state))return false;
        int[] e=ENDS[RailState.shape(state)];
        double ax=bx+.5+e[0]*.5,az=bz+.5+e[2]*.5,dx=(e[3]-e[0])*.5,dz=(e[5]-e[2])*.5;
        double t=dx==0?z-bz:dz==0?x-bx:((x-ax)*dx+(z-az)*dz)*2;
        out[0]=ax+dx*t;out[2]=az+dz*t;
        double dy=e[4]-e[1];out[1]=by+.0625+e[1]*.5+dy*t+(dy<0?1:dy>0?.5:0);
        return true;
    }
    private static boolean offset(WorldChunkStore world,double x,double y,double z,double distance,double[] out) {
        int bx=(int)Math.floor(x),by=(int)Math.floor(y),bz=(int)Math.floor(z);
        if(RailState.isRail(world.blockStateAt(bx,by-1,bz)))by--;
        int state=world.blockStateAt(bx,by,bz);if(!RailState.isRail(state))return false;
        int shape=RailState.shape(state);int[] e=ENDS[shape];y=by+(shape>=2&&shape<=5?1:0);
        double dx=e[3]-e[0],dz=e[5]-e[2],length=Math.sqrt(dx*dx+dz*dz);
        x+=dx/length*distance;z+=dz/length*distance;
        int rx=(int)Math.floor(x)-bx,rz=(int)Math.floor(z)-bz;
        if(e[1]!=0&&rx==e[0]&&rz==e[2])y+=e[1];
        else if(e[4]!=0&&rx==e[3]&&rz==e[5])y+=e[4];
        return project(world,x,y,z,out);
    }
    public static boolean pose(WorldChunkStore world,EntityTracker.Renderable cart,double[] center,double[] front,double[] back) {
        if(!project(world,cart.x,cart.y,cart.z,center))return false;
        if(!offset(world,cart.x,cart.y,cart.z,.3,front))System.arraycopy(center,0,front,0,3);
        if(!offset(world,cart.x,cart.y,cart.z,-.3,back))System.arraycopy(center,0,back,0,3);
        cart.x=(float)center[0];cart.y=(float)((front[1]+back[1])*.5);cart.z=(float)center[2];
        double dx=back[0]-front[0],dy=back[1]-front[1],dz=back[2]-front[2],length=Math.sqrt(dx*dx+dy*dy+dz*dz);
        if(length>1e-9) {cart.yaw=(float)Math.toDegrees(Math.atan2(dz,dx));cart.pitch=(float)(-Math.atan(dy/length)*73);}
        return true;
    }
}
