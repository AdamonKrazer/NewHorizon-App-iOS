package com.newhorizon.thinclient.world;

/** Forward edges, rather than repeated key-down callbacks, arm vanilla's seven-tick gesture. */
public final class SprintInput {
    private boolean held,armed;
    private long lastPress;
    public boolean forward(boolean down,long now,boolean enabled) {
        if(down==held)return false;
        held=down;if(!down)return false;
        boolean twice=enabled&&armed&&now>=lastPress&&now-lastPress<=350_000_000L;
        lastPress=now;armed=enabled&&!twice;return twice;
    }
    public void cancel(){armed=false;}
    public void reset(){held=armed=false;}
    /** LocalPlayer.isHorizontalCollisionMinor: less than eight degrees of deflection. */
    public static boolean minorCollision(double inputX,double inputZ,double movedX,double movedZ) {
        double a=inputX*inputX+inputZ*inputZ,b=movedX*movedX+movedZ*movedZ;
        if(a<1e-5||b<1e-5)return false;
        double cosine=(inputX*movedX+inputZ*movedZ)/Math.sqrt(a*b);
        return cosine>Math.cos(0.13962633907794952);
    }
    public static boolean blocked(boolean collision,boolean minor,boolean swimming,boolean flying) {
        return collision&&!minor&&!swimming&&!flying;
    }
}
