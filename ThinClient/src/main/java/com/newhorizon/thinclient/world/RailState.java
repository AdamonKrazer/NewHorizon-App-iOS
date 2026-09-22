package com.newhorizon.thinclient.world;

/** All 92 rail states from the official 1.20.1 block report and block models. */
public final class RailState {
    private RailState() {}
    private static final short[] DATA = {192,192,209,209,210,210,211,211,196,196,197,197,128,128,145,145,146,146,147,147,132,132,133,133,320,320,337,337,338,338,339,339,324,324,325,325,256,256,273,273,274,274,275,275,260,260,261,261,0,0,17,17,18,18,19,19,4,4,5,5,70,70,87,87,104,104,121,121,448,448,465,465,466,466,467,467,452,452,453,453,384,384,401,401,402,402,403,403,388,388,389,389};
    public static int data(int state) { int i=state>=1944&&state<=1991?state-1944:state>=4662&&state<=4681?state-4662+48:state>=9180&&state<=9203?state-9180+68:-1; return i<0?-1:DATA[i]; }
    public static boolean isRail(int state) { return data(state)>=0; }
    public static int shape(int state) { return data(state)&15; }
    public static float outlineHeight(int state) { int s=shape(state); return data(state)<0?1:s>=2&&s<=5?.625f:.125f; }
    public static float surfaceHeight(int shape,float x,float z) {
        return .0625f+(shape==2?x:shape==3?1-x:shape==4?1-z:shape==5?z:0);
    }
    /** Exact outline ray intersection, independent of rendering and collision. */
    public static double hit(int state,double ox,double oy,double oz,double dx,double dy,double dz,int x,int y,int z,double max) {
        double near=0,far=max;
        for(int axis=0;axis<3;axis++) {
            double o=axis==0?ox:axis==1?oy:oz, d=axis==0?dx:axis==1?dy:dz;
            double lo=axis==0?x:axis==1?y:z, hi=lo+(axis==1?outlineHeight(state):1);
            if(Math.abs(d)<1e-12) { if(o<lo||o>hi)return -1; }
            else { double a=(lo-o)/d,b=(hi-o)/d;near=Math.max(near,Math.min(a,b));far=Math.min(far,Math.max(a,b));if(near>far)return -1; }
        }
        return near;
    }
}
