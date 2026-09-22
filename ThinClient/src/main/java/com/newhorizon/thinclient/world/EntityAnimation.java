package com.newhorizon.thinclient.world;

/** Bounded, 20 Hz presentation state. Positions and gameplay remain server-owned. */
final class EntityAnimation {
    static final long TICK=50_000_000L;
    long tickTime;
    private double x,y,z;
    private float age,walk,amount,oldAmount,jumpTicks;
    private float swim,oldSwim;
    private float phase,tentacle,oldTentacle,rate,spinSpeed,tilt,oldTilt,roll,oldRoll,yaw,oldYaw;
    void reset(int id,double x,double y,double z,float yaw,long now) {
        this.x=x;this.y=y;this.z=z;tickTime=now;
        age=walk=amount=oldAmount=phase=tentacle=oldTentacle=spinSpeed=tilt=oldTilt=roll=oldRoll=0;jumpTicks=10;
        this.yaw=oldYaw=yaw;
        swim=oldSwim=0;
        rate=.2f/(new java.util.Random(id).nextFloat()+1);
    }
    void resetSquidCycle(){phase=0;}
    void swimming(boolean prone){oldSwim=swim;swim=Math.max(0,Math.min(1,swim+(prone?.09f:-.09f)));}
    void jump(){jumpTicks=0;}
    void relocate(double x,double y,double z){this.x=x;this.y=y;this.z=z;amount=oldAmount=0;}
    void tick(double nx,double ny,double nz,int type,boolean still,boolean water,
              double vx,double vy,double vz) {
        double dx=nx-x,dy=ny-y,dz=nz-z;x=nx;y=ny;z=nz;
        age++;jumpTicks=Math.min(10,jumpTicks+1);oldAmount=amount;
        // WalkAnimationState.update(min(distance * 4, 1), .4). Teleports do not stride.
        double distance=dx*dx+dz*dz;
        float target=still||distance+dy*dy>64?0:Math.min(1,(float)Math.sqrt(distance)*4);
        amount+=(target-amount)*.4f;walk+=amount;
        oldTentacle=tentacle;oldTilt=tilt;oldRoll=roll;oldYaw=yaw;
        if(type!=44&&type!=96)return;
        // Some servers omit squid velocity updates. Relative movement still contains
        // the authoritative swim direction; never leave a moving squid upright.
        if(vx*vx+vy*vy+vz*vz<1e-10&&distance+dy*dy>1e-10&&distance+dy*dy<64){vx=dx;vy=dy;vz=dz;}
        phase=Math.min((float)(Math.PI*2),phase+rate);
        if(water) {
            if(phase<Math.PI) {
                float f=phase/(float)Math.PI;
                tentacle=(float)Math.sin(f*f*Math.PI)*(float)Math.PI*.25f;
                if(f>.75f)spinSpeed=1;else spinSpeed*=.8f;
            } else {tentacle=0;spinSpeed*=.99f;}
            double horizontal=Math.sqrt(vx*vx+vz*vz);
            if(horizontal+Math.abs(vy)>1e-6) {
                yaw+=ChickenBodyRotation.wrap((float)-Math.toDegrees(Math.atan2(vx,vz))-yaw)*.1f;
                tilt+=((float)-Math.toDegrees(Math.atan2(horizontal,vy))-tilt)*.1f;
            }
            roll+=(float)Math.PI*spinSpeed*1.5f;
        } else {
            tentacle=Math.abs((float)Math.sin(phase))*(float)Math.PI*.25f;
            tilt+=(-90-tilt)*.02f;
        }
    }
    void snapshot(EntityTracker.Renderable e,long now) {
        float partial=Math.max(0,Math.min(1,(now-tickTime)/(float)TICK));
        e.age=age+partial;e.walkPosition=walk-amount*(1-partial);
        e.jumpProgress=Math.min(1,(jumpTicks+partial)/10);
        e.walkSpeed=oldAmount+(amount-oldAmount)*partial;
        e.swimAmount=oldSwim+(swim-oldSwim)*partial;
        if(e.baby)e.walkPosition*=3;
        if(e.riding||e.sleeping||e.deathProgress>0)e.walkSpeed=0;
        e.squidTentacle=oldTentacle+(tentacle-oldTentacle)*partial;
        e.squidPitch=oldTilt+(tilt-oldTilt)*partial;
        e.squidRoll=oldRoll+(roll-oldRoll)*partial;
        if(e.type==44||e.type==96)e.bodyYaw=ChickenBodyRotation.interpolate(oldYaw,yaw,partial);
    }
}
