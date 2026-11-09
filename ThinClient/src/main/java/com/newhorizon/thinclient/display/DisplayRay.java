package com.newhorizon.thinclient.display;

/** Same plane and UV basis as the rendered display, with front-face and range checks. */
public final class DisplayRay {
    private static final int[][] RIGHT={{1,0,0},{1,0,0},{-1,0,0},{1,0,0},{0,0,1},{0,0,-1}};
    private static final int[][] UP={{0,0,-1},{0,0,-1},{0,1,0},{0,1,0},{0,1,0},{0,1,0}};
    private static final int[][] NORMAL={{0,-1,0},{0,1,0},{0,0,-1},{0,0,1},{-1,0,0},{1,0,0}};
    public static final class Hit {public double distance,u,v;}
    private DisplayRay(){}
    public static boolean hit(double ox,double oy,double oz,double dx,double dy,double dz,
            double cx,double cy,double cz,double width,double height,int side,double limit,Hit out){
        if(side<0||side>=6||width<=0||height<=0||!Double.isFinite(limit)||limit<=0)return false;
        int[] n=NORMAL[side],r=RIGHT[side],up=UP[side];double dot=dx*n[0]+dy*n[1]+dz*n[2];
        if(dot>=-1e-7)return false;
        cx+=n[0]*.008;cy+=n[1]*.008;cz+=n[2]*.008;
        double distance=((cx-ox)*n[0]+(cy-oy)*n[1]+(cz-oz)*n[2])/dot;
        if(!Double.isFinite(distance)||distance<.001||distance>limit)return false;
        double x=ox+dx*distance-cx,y=oy+dy*distance-cy,z=oz+dz*distance-cz;
        double u=.5+(x*r[0]+y*r[1]+z*r[2])/width,v=.5-(x*up[0]+y*up[1]+z*up[2])/height;
        if(!Double.isFinite(u)||!Double.isFinite(v)||u<0||u>1||v<0||v>1)return false;
        out.distance=distance;out.u=u;out.v=v;return true;
    }
    public static int pixel(double coordinate,int size){return Math.max(0,Math.min(size-1,(int)(coordinate*size)));}
}
