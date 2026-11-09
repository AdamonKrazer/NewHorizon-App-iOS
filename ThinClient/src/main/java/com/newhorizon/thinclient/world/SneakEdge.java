package com.newhorizon.thinclient.world;

/** Player.maybeBackOffFromEdge: test each axis, then their diagonal, in 0.05-block increments. */
public final class SneakEdge {
    public interface Support { boolean at(double dx,double dz); }
    public static void clip(double dx,double dz,Support support,double[] out) {
        while(dx!=0&&!support.at(dx,0))dx=reduce(dx);
        while(dz!=0&&!support.at(0,dz))dz=reduce(dz);
        while(dx!=0&&dz!=0&&!support.at(dx,dz)){dx=reduce(dx);dz=reduce(dz);}
        out[0]=dx;out[1]=dz;
    }
    private static double reduce(double value){return Math.abs(value)<=.05?0:value-Math.copySign(.05,value);}
    private SneakEdge(){}
}
