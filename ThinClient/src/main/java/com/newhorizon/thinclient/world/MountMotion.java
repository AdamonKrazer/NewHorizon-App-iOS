package com.newhorizon.thinclient.world;
/** Locally controlled saddled land mount. Damage, taming, saddle and attributes come from the server. */
public final class MountMotion {
    private MountMotion(){}
    public static void tick(BoatMotion motion,WorldChunkStore world,EntityTracker.Vehicle mount,float forward,float sideways,float yaw,float jumpPower){
        double radius=CombatHitboxes.width(mount.type)/2,height=CombatHitboxes.height(mount.type);
        motion.yaw=yaw;motion.pitch=0;
        motion.onGround=world.collidesBox(motion.x-radius,motion.y-.001,motion.z-radius,motion.x+radius,motion.y,motion.z+radius);
        float strafe=sideways*.5f;if(forward<=0)forward*=.25f;
        if(jumpPower>0&&motion.onGround){motion.vy=mount.jump*jumpPower;motion.onGround=false;if(forward>0){motion.vx-=Math.sin(Math.toRadians(yaw))*.4*jumpPower;motion.vz+=Math.cos(Math.toRadians(yaw))*.4*jumpPower;}}
        float friction=world.frictionAt((int)Math.floor(motion.x),(int)Math.floor(motion.y-.5000001),(int)Math.floor(motion.z));
        double speed=motion.onGround?mount.speed*.21600002/(friction*friction*friction):.02;
        double length=Math.sqrt(forward*forward+strafe*strafe);if(length>1){forward/=length;strafe/=length;}
        double angle=Math.toRadians(yaw);motion.vx+=(strafe*Math.cos(angle)-forward*Math.sin(angle))*speed;
        motion.vz+=(forward*Math.cos(angle)+strafe*Math.sin(angle))*speed;
        motion.move(world,radius,height,1);motion.vy=(motion.vy-.08)*.98;
        double drag=motion.onGround?friction*.91:.91;motion.vx*=drag;motion.vz*=drag;
    }
    public static float jumpPower(int ticks){if(ticks>=10)return .8f+.2f/(ticks-9);return Math.max(0,ticks)*.1f;}
}
