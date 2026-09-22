package com.newhorizon.thinclient.world;

/** Local presentation follows the same tick clock as remote living entities. */
public final class LocalPlayerVisual {
    public final EntityTracker.Renderable model=new EntityTracker.Renderable();
    private final EntityAnimation animation=new EntityAnimation();
    private final ChickenBodyRotation rotation=new ChickenBodyRotation();
    private boolean initialized;
    private long tick;
    private double lastX,lastZ;
    public void update(long now,double x,double y,double z,float yaw,float pitch){
        if(!initialized){animation.reset(0,x,y,z,yaw,now);rotation.reset(x,z,yaw,yaw,now);tick=now;initialized=true;lastX=x;lastZ=z;}
        if(now-tick>=50_000_000L){
            animation.swimming(model.prone);
            double dx=x-lastX,dz=z-lastZ;float body=yaw;
            if(dx*dx+dz*dz>1e-5){body=(float)-Math.toDegrees(Math.atan2(dx,dz));if(Math.abs(ChickenBodyRotation.wrap(yaw-body))>90)body+=180;}
            rotation.tick(x,z,body,yaw,false);rotation.tickNanos=now;
            animation.tick(x,y,z,122,model.riding||model.sleeping,model.inWater,0,0,0);animation.tickTime=now;tick=now;lastX=x;lastZ=z;
        }
        model.x=(float)x;model.y=(float)y-(model.sneaking?.125f:0);model.z=(float)z;model.yaw=yaw;model.pitch=pitch;
        model.bodyYaw=rotation.bodyYaw(now);model.headYaw=yaw;model.type=122;model.player=true;model.appearance[17]=127;
        animation.snapshot(model,now);
    }
}
