package com.newhorizon.thinclient.render;

import java.nio.ByteBuffer;
import com.newhorizon.thinclient.world.Rope;

/** Fixed 24-segment crossed leash ribbons and 16-segment fishing lines. */
public final class RopeMesh {
    public static final int MAX_ROPES=64, BYTES=MAX_ROPES*24*12*16;
    private RopeMesh(){}
    public static int mesh(ByteBuffer out,Rope[] ropes,int count,double cx,double cy,double cz) {
        out.clear();
        for(int i=0;i<Math.min(count,MAX_ROPES);i++) {
            Rope r=ropes[i];int steps=r.fishing?16:24;
            if(out.remaining()<steps*(r.fishing?6:12)*16)break;
            double dx=r.endX-r.x,dz=r.endZ-r.z,flat=Math.sqrt(dx*dx+dz*dz);
            double sideX=flat<1e-8?.0125:dz/flat*.0125,sideZ=flat<1e-8?0:-dx/flat*.0125;
            for(int step=0;step<steps;step++) {
                double a=step/(double)steps,b=(step+1)/(double)steps;
                double x0=r.x+dx*a,y0=Rope.height(r.y,r.endY,a,r.fishing),z0=r.z+dz*a;
                double x1=r.x+dx*b,y1=Rope.height(r.y,r.endY,b,r.fishing),z1=r.z+dz*b;
                if(r.fishing) {
                    // One screen-facing ribbon stays visible at distance without a thick world-space cable.
                    double vx=cx-(x0+x1)*.5,vy=cy-(y0+y1)*.5,vz=cz-(z0+z1)*.5;
                    double sx=(y1-y0)*vz-(z1-z0)*vy,sy=(z1-z0)*vx-(x1-x0)*vz,sz=(x1-x0)*vy-(y1-y0)*vx;
                    double len=Math.sqrt(sx*sx+sy*sy+sz*sz),width=Math.max(.0015,Math.sqrt(vx*vx+vy*vy+vz*vz)*.0005);
                    if(len<1e-9){sx=1;sy=sz=0;len=1;}
                    quad(out,x0,y0,z0,x1,y1,z1,sx/len*width,sy/len*width,sz/len*width,0x080808ff);
                } else {
                    int color=step%2==0?0x594735ff:0x80664dff;
                    quad(out,x0,y0+.0125,z0,x1,y1+.0125,z1,sideX,0,sideZ,color);
                    quad(out,x0,y0+.0125,z0,x1,y1+.0125,z1,0,.0125,0,step%2==0?0x80664dff:0x594735ff);
                }
            }
        }
        return out.position()/16;
    }
    private static void quad(ByteBuffer b,double x,double y,double z,double ex,double ey,double ez,double sx,double sy,double sz,int c) {
        v(b,x-sx,y-sy,z-sz,c);v(b,ex-sx,ey-sy,ez-sz,c);v(b,ex+sx,ey+sy,ez+sz,c);
        v(b,x-sx,y-sy,z-sz,c);v(b,ex+sx,ey+sy,ez+sz,c);v(b,x+sx,y+sy,z+sz,c);
    }
    private static void v(ByteBuffer b,double x,double y,double z,int c) {b.putFloat((float)x).putFloat((float)y).putFloat((float)z).put((byte)(c>>24)).put((byte)(c>>16)).put((byte)(c>>8)).put((byte)c);}
}
