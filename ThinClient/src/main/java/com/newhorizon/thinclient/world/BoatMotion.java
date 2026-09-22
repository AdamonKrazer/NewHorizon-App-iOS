package com.newhorizon.thinclient.world;

/** Local controlling passenger's 20 Hz boat simulation, using vanilla voxel collisions. */
public final class BoatMotion {
    public static final int WATER=0,UNDER_WATER=1,FLOWING=2,LAND=3,AIR=4;
    public double x,y,z,vx,vy,vz;
    public float yaw,pitch,turn;
    public boolean leftPaddle,rightPaddle,onGround;
    private int previousStatus=AIR,bubbleTicks;
    public void reset(double x,double y,double z,float yaw,float pitch){this.x=x;this.y=y;this.z=z;this.yaw=yaw;this.pitch=pitch;vx=vy=vz=0;turn=0;previousStatus=AIR;bubbleTicks=0;}
    public void tick(WorldChunkStore world,boolean left,boolean right,boolean forward,boolean back){
        if(world.blockStateAt((int)Math.floor(x),(int)Math.floor(y),(int)Math.floor(z))<0)return;
        double depth=world.fluidDepthInBox(BlockStatePhysics.WATER,x-.6875,y,z-.6875,x+.6875,y+.5625,z+.6875);
        int top=world.physicsFlagsInBox(x-.6875,y+.5625,z-.6875,x+.6875,y+.5635,z+.6875);
        int status=(top&BlockStatePhysics.WATER)!=0?((top&BlockStatePhysics.FLUID_FALLING)!=0?FLOWING:UNDER_WATER)
                :depth>0?WATER:world.collidesBox(x-.6875,y-.001,z-.6875,x+.6875,y,z+.6875)?LAND:AIR;
        float friction=groundFriction(world);
        integrate(status,depth,friction,left,right,forward,back);
        move(world,.6875,.5625,0);
        int flags=world.physicsFlagsInBox(x-.68,y-.01,z-.68,x+.68,y+.01,z+.68);
        if((flags&(BlockStatePhysics.BUBBLE_UP|BlockStatePhysics.BUBBLE_DOWN))!=0){
            if(++bubbleTicks>=60){vy=(flags&BlockStatePhysics.BUBBLE_DOWN)!=0?vy-.7:2.7;bubbleTicks=0;}
        }else bubbleTicks=0;
    }
    /** Exposed pure tick for deterministic water/control tests. */
    public void integrate(int status,double waterDepth,float landFriction,boolean left,boolean right,boolean forward,boolean back){
        if(previousStatus==AIR&&status!=AIR&&status!=LAND){y+=waterDepth-.5625+.101;vy=0;status=WATER;}
        else {
            double gravity=-.03999999910593033,buoyancy=0;float drag=.05f;
            switch(status){case WATER:buoyancy=waterDepth/.5625;drag=.9f;break;
                case UNDER_WATER:buoyancy=.009999999776482582;drag=.45f;break;
                case FLOWING:gravity=-.0007;drag=.9f;break;
                case AIR:drag=.9f;break;case LAND:drag=landFriction;break;default:break;}
            vx*=drag;vz*=drag;vy+=gravity;turn*=drag;
            if(buoyancy>0)vy=(vy+buoyancy*.06153846016296973)*.75;
        }
        previousStatus=status;
        if(left)turn--;if(right)turn++;float thrust=0;
        if(left!=right&&!forward&&!back)thrust+=.005f;
        yaw+=turn;if(forward)thrust+=.04f;if(back)thrust-=.005f;
        double angle=Math.toRadians(yaw);vx-=Math.sin(angle)*thrust;vz+=Math.cos(angle)*thrust;
        leftPaddle=(right&&!left)||forward;rightPaddle=(left&&!right)||forward;
    }
    private float groundFriction(WorldChunkStore world){
        float sum=0;int count=0;
        for(int ix=(int)Math.floor(x-.6875);ix<=Math.floor(x+.6875);ix++)for(int iz=(int)Math.floor(z-.6875);iz<=Math.floor(z+.6875);iz++){
            int iy=(int)Math.floor(y-.001),state=world.blockStateAt(ix,iy,iz);
            if(state>=0&&BlockStatePhysics.blocksMovement(state)){sum+=BlockStatePhysics.friction(state);count++;}
        }
        return count==0?.6f:sum/count;
    }
    public void move(WorldChunkStore world,double radius,double height,double stepHeight){
        double startX=x,startY=y,startZ=z;
        double dy=clip(world,1,vy,radius,height);y+=dy;onGround=vy<0&&dy!=vy;if(dy!=vy)vy=0;
        boolean zFirst=Math.abs(vz)>Math.abs(vx);
        double dx=0,dz=0;
        if(zFirst){dz=clip(world,2,vz,radius,height);z+=dz;}
        dx=clip(world,0,vx,radius,height);x+=dx;
        if(!zFirst){dz=clip(world,2,vz,radius,height);z+=dz;}
        if(stepHeight>0&&onGround&&(dx!=vx||dz!=vz)){
            double clippedX=x,clippedY=y,clippedZ=z;x=startX;y=startY;z=startZ;
            double up=clip(world,1,stepHeight,radius,height);y+=up;
            double sx=clip(world,0,vx,radius,height);x+=sx;double sz=clip(world,2,vz,radius,height);z+=sz;
            if(sx*sx+sz*sz>dx*dx+dz*dz){y+=clip(world,1,-up,radius,height);dx=sx;dz=sz;}
            else{x=clippedX;y=clippedY;z=clippedZ;}
        }
        if(dx!=vx)vx=0;if(dz!=vz)vz=0;
    }
    private double clip(WorldChunkStore world,int axis,double delta,double radius,double height){return world.clipMovement(axis,x-radius,y,z-radius,x+radius,y+height,z+radius,delta);}
}
