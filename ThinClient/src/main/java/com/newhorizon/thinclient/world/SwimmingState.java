package com.newhorizon.thinclient.world;

/** Entity.updateSwimming and Player.travel, with velocities in blocks/second. */
public final class SwimmingState {
    public boolean swimming, prone;

    public void update(boolean sprint, boolean water, boolean eyesUnderwater,
                       boolean feetWater, boolean disabled,
                       boolean standingFits, boolean crouchingFits) {
        swimming=!disabled && sprint && (swimming?water:eyesUnderwater&&feetWater);
        prone=!disabled && (swimming || !standingFits&&!crouchingFits);
    }

    public static double steer(double velocity, float pitch, boolean jumping, boolean waterAbove) {
        double lookY=-Math.sin(Math.toRadians(pitch));
        if(lookY<=0 || jumping || waterAbove)
            velocity+=(lookY-velocity/20)*(lookY<-.2?.085:.06)*20;
        return velocity;
    }
}
