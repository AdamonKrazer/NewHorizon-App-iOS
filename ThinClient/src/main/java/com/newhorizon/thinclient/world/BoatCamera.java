package com.newhorizon.thinclient.world;

/** Carries the view with the rendered boat, using the same partial tick as its seat. */
public final class BoatCamera {
    private float previousYaw;
    private boolean initialized;
    public void reset() { initialized=false; }
    public float update(float cameraYaw,float renderedYaw) {
        if(initialized)cameraYaw+=wrap(renderedYaw-previousYaw);
        previousYaw=renderedYaw;initialized=true;
        return renderedYaw+Math.max(-105,Math.min(105,wrap(cameraYaw-renderedYaw)));
    }
    public static float interpolate(float previous,float current,double alpha) {
        return previous+wrap(current-previous)*(float)Math.max(0,Math.min(1,alpha));
    }
    private static float wrap(float angle) {
        angle%=360;return angle>=180?angle-360:angle< -180?angle+360:angle;
    }
}
