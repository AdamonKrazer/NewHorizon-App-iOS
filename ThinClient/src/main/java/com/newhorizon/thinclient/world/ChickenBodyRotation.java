package com.newhorizon.thinclient.world;

/** Presentation-only Java 1.20.1 BodyRotationControl for the chicken model. */
final class ChickenBodyRotation {
    static final long TICK_NANOS = 50_000_000L;
    private static final double MOVING_DISTANCE_SQUARED = 2.500000277905201E-7;
    private static final float MAX_HEAD_YAW = 75.0f;
    long tickNanos;
    private double lastX, lastZ;
    private float bodyYaw, previousBodyYaw, headYaw, previousHeadYaw, stableHeadYaw;
    private int stableTicks;

    void reset(double x, double z, float yaw, float head, long now) {
        lastX=x; lastZ=z; tickNanos=now;
        bodyYaw=previousBodyYaw=yaw;
        headYaw=previousHeadYaw=head;
        stableHeadYaw=0; stableTicks=0;
    }

    void tick(double x, double z, float entityYaw, float networkHeadYaw,
              boolean carryingMobPassenger) {
        previousBodyYaw=bodyYaw; previousHeadYaw=headYaw;
        headYaw=networkHeadYaw;
        double dx=x-lastX, dz=z-lastZ;
        lastX=x; lastZ=z;
        if(dx*dx+dz*dz>MOVING_DISTANCE_SQUARED) {
            bodyYaw=entityYaw;
            headYaw=limit(headYaw,bodyYaw,MAX_HEAD_YAW);
            stableHeadYaw=headYaw;
            stableTicks=0;
        } else if(!carryingMobPassenger) {
            if(Math.abs(headYaw-stableHeadYaw)>15.0f) {
                stableTicks=0;
                stableHeadYaw=headYaw;
                bodyYaw=limit(bodyYaw,headYaw,MAX_HEAD_YAW);
            } else {
                // Twenty ticks already produce the fully aligned state.
                stableTicks=Math.min(20,stableTicks+1);
                if(stableTicks>10) {
                    float fraction=Math.min(1.0f,(stableTicks-10)/10.0f);
                    bodyYaw=limit(bodyYaw,headYaw,MAX_HEAD_YAW*(1.0f-fraction));
                }
            }
        }
    }

    float bodyYaw(long now) { return interpolate(previousBodyYaw,bodyYaw,partial(now)); }
    float headYaw(long now) { return interpolate(previousHeadYaw,headYaw,partial(now)); }
    private float partial(long now) {
        return Math.max(0,Math.min(1,(now-tickNanos)/(float)TICK_NANOS));
    }
    static float limit(float angle, float target, float maximum) {
        return target-Math.max(-maximum,Math.min(maximum,wrap(target-angle)));
    }
    static float interpolate(float from,float to,float partial) {
        return from+wrap(to-from)*partial;
    }
    static float wrap(float angle) {
        angle%=360.0f;
        if(angle>=180.0f)angle-=360.0f;
        if(angle< -180.0f)angle+=360.0f;
        return angle;
    }
}
