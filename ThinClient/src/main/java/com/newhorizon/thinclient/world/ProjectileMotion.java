package com.newhorizon.thinclient.world;

/** Small visual simulation; never creates damage, explosions, drops or teleports. */
final class ProjectileMotion {
    private static final long TICK = 50_000_000L;
    int type, itemId, color, flags, loyalty;
    boolean active, noGravity, stopped, dangerous;
    double x,y,z,px,py,pz,vx,vy,vz,ax,ay,az;
    float yaw,pitch;
    long tickTime, trailTime;
    boolean shotAtAngle, horizontalCollision, exploded;
    int attachedEntity=-1;
    FireworkData firework;
    int owner=-1,hooked=-1;
    boolean biting,bobbing;
    private final java.util.Random fishingRandom=new java.util.Random();
    void biting(boolean value) {if(value&&!biting)vy=-.4*(.6+fishingRandom.nextFloat()*.4);biting=value;}
    private void fishingTick(WorldChunkStore world) {
        if(hooked>=0)return;
        int bx=(int)Math.floor(x),by=(int)Math.floor(y),bz=(int)Math.floor(z);
        double water=world==null?0:world.waterHeightAt(bx,by,bz);
        if(!bobbing&&water>0) {vx*=.3;vy*=.2;vz*=.3;bobbing=true;return;}
        if(bobbing) {
            double depth=y+vy-by-water;if(Math.abs(depth)<.01)depth+=Math.signum(depth)*.1;
            vx*=.9;vz*=.9;vy-=depth*fishingRandom.nextFloat()*.2;
            if(biting&&water>0)vy-=.1*fishingRandom.nextFloat()*fishingRandom.nextFloat();
        }
        if(water==0&&!noGravity)vy-=.03;
        double hit=world==null?1:world.projectileClip(x,y,z,vx,vy,vz);
        x+=vx*hit;y+=vy*hit;z+=vz*hit;
        if(hit<1)vx=vy=vz=0;
        vx*=.92;vy*=.92;vz*=.92;
    }

    void reset(int type, double x, double y, double z, float yaw, float pitch,
               double vx, double vy, double vz, long now) {
        this.type=type; active=ProjectileKind.isProjectile(type);
        this.x=px=x; this.y=py=y; this.z=pz=z;
        this.yaw=yaw; this.pitch=pitch; tickTime=now;
        owner=hooked=-1;biting=bobbing=false;
        shotAtAngle=horizontalCollision=exploded=false;attachedEntity=-1;firework=null;trailTime=now;
        noGravity=stopped=dangerous=false; itemId=flags=loyalty=0; color=-1;
        this.vx=vx; this.vy=vy; this.vz=vz; ax=ay=az=0;
        if (ProjectileKind.accelerating(type)) {
            // AddEntity stores the acceleration direction for hurting projectiles.
            double length=Math.sqrt(vx*vx+vy*vy+vz*vz);
            if (length>0) { ax=vx/length*.1; ay=vy/length*.1; az=vz/length*.1; }
            this.vx=this.vy=this.vz=0;
        }
    }

    void correct(double x, double y, double z, float yaw, float pitch, long now) {
        this.x=px=x; this.y=py=y; this.z=pz=z;
        this.yaw=yaw; this.pitch=pitch; tickTime=now; stopped=false;
    }

    void velocity(double vx, double vy, double vz) {
        this.vx=vx; this.vy=vy; this.vz=vz; stopped=false;
    }

    void advance(long now, WorldChunkStore world) {
        if (!active) return;
        // A long pause must not run an unbounded physics backlog on the render thread.
        if (now-tickTime > 5*TICK) tickTime=now-5*TICK;
        while (now-tickTime >= TICK) {
            tickTime+=TICK; px=x; py=y; pz=z;
            if(type==ProjectileKind.FISHING) {fishingTick(world);continue;}
            if (stopped || exploded || (type==ProjectileKind.FIREWORK && attachedEntity>=0)) continue;
            if(type==ProjectileKind.FIREWORK && !shotAtAngle) {
                double acceleration=horizontalCollision?1:1.15;vx*=acceleration;vz*=acceleration;vy+=.04;
            }
            // Homing/attached variants follow server corrections and velocity.
            double dx=vx,dy=vy,dz=vz;
            double length=Math.sqrt(dx*dx+dy*dy+dz*dz);
            if (length > 16) { dx*=16/length; dy*=16/length; dz*=16/length; }
            double hit=world == null ? 1 : world.projectileClip(x,y,z,dx,dy,dz);
            x+=dx*hit; y+=dy*hit; z+=dz*hit;
            if (length>1e-7) {
                yaw=(float)Math.toDegrees(Math.atan2(dx,dz));
                pitch=(float)Math.toDegrees(Math.atan2(dy,Math.sqrt(dx*dx+dz*dz)));
            }
            if (hit<1 && type!=ProjectileKind.FIREWORK) { stopped=true; continue; }
            horizontalCollision=hit<1 && (dx!=0||dz!=0);
            int state=world == null ? 0 : world.blockStateAt((int)Math.floor(x),
                    (int)Math.floor(y),(int)Math.floor(z));
            boolean water=state>=0 && (BlockStatePhysics.flags(state)&BlockStatePhysics.WATER)!=0;
            double drag=ProjectileKind.accelerating(type) ? (water ? (double).8f
                    : dangerous && type==ProjectileKind.WITHER_SKULL ? (double).73f : (double).95f)
                    : water ? (ProjectileKind.arrow(type) ? (type==ProjectileKind.TRIDENT ? .99 : .6) : .8)
                    : (double).99f;
            if (type==ProjectileKind.FIREWORK || type==ProjectileKind.SHULKER) drag=1;
            vx=(vx+ax)*drag; vy=(vy+ay)*drag; vz=(vz+az)*drag;
            if (!noGravity) vy-=ProjectileKind.gravity(type);
        }
    }

    double progress(long now) { return Math.max(0,Math.min(1,(now-tickTime)/(double)TICK)); }
    double renderX(long now) { return px+(x-px)*progress(now); }
    double renderY(long now) { return py+(y-py)*progress(now); }
    double renderZ(long now) { return pz+(z-pz)*progress(now); }
}
