package com.newhorizon.thinclient.world;

/** Local presentation follows the same tick clock as remote living entities. */
public final class LocalPlayerVisual {
    public final EntityTracker.Renderable model=new EntityTracker.Renderable();
    private final EntityAnimation animation=new EntityAnimation();
    public LocalPlayerVisual(){model.appearance[18]=1;}
    private boolean initialized;
    private long tick;
    private double lastX,lastZ;
    private float bodyYaw,previousBodyYaw;
    public void update(long now,double x,double y,double z,float yaw,float pitch){
        if(!initialized){animation.reset(0,x,y,z,yaw,now);bodyYaw=previousBodyYaw=yaw;tick=now;initialized=true;lastX=x;lastZ=z;}
        if(now-tick>=50_000_000L){
            animation.swimming(model.prone);
            previousBodyYaw=bodyYaw;
            double dx=x-lastX,dz=z-lastZ;float target=bodyYaw;
            // LivingEntity.tick/tickHeadTurn (1.20.1), not the mob BodyRotationControl.
            // The five-degree margin keeps tiny depth noise during strafing from
            // alternating between forward and backward headings every tick.
            if(dx*dx+dz*dz>0.0025000002f){
                target=(float)-Math.toDegrees(Math.atan2(dx,dz));
                if(Math.abs(ChickenBodyRotation.wrap(yaw-target))>95)target-=180;
            }
            if(model.swingProgress>0)target=yaw;
            bodyYaw+=ChickenBodyRotation.wrap(target-bodyYaw)*.3f;
            float headDelta=ChickenBodyRotation.wrap(yaw-bodyYaw);
            if(Math.abs(headDelta)>50)bodyYaw+=headDelta-Math.signum(headDelta)*50;
            animation.tick(x,y,z,122,model.riding||model.sleeping,model.inWater,0,0,0);animation.tickTime=now;tick=now;lastX=x;lastZ=z;
        }
        model.x=(float)x;model.y=(float)y-(model.sneaking?.125f:0);model.z=(float)z;model.yaw=yaw;model.pitch=pitch;
        float partial=Math.max(0,Math.min(1,(now-tick)/50_000_000f));
        model.bodyYaw=ChickenBodyRotation.interpolate(previousBodyYaw,bodyYaw,partial);model.headYaw=yaw;model.type=122;model.player=true;model.appearance[17]=127;
        animation.snapshot(model,now);
    }
}
