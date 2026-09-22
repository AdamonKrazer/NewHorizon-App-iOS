package com.newhorizon.thinclient.world;

/** Reused render snapshot. Fishing goes from hook to hand; leash from mob to holder. */
public final class Rope {
    public double x,y,z,endX,endY,endZ;
    public boolean fishing;
    public static double height(double start,double end,double t,boolean fishing) {
        double dy=end-start;
        return start+(fishing?dy*(t*t+t)*.5:dy>0?dy*t*t:dy-dy*(1-t)*(1-t));
    }
}
