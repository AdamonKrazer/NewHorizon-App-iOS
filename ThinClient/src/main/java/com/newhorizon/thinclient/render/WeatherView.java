package com.newhorizon.thinclient.render;

/** Conservative camera planes for precipitation; no allocation in the render loop. */
public final class WeatherView {
    private final double[][] planes=new double[5][3];
    private double x,y,z;
    public void camera(double x,double y,double z,float yaw,float pitch,float aspect,float projection) {
        this.x=x;this.y=y;this.z=z;
        double sy=Math.sin(Math.toRadians(yaw)),cy=Math.cos(Math.toRadians(yaw));
        double sp=Math.sin(Math.toRadians(pitch)),cp=Math.cos(Math.toRadians(pitch));
        // Expanded sides retain geometry during nausea/hurt projection effects.
        double horizontal=projection*.7/aspect,vertical=projection*.7;
        for(int i=0;i<4;i++) {
            double sign=(i&1)==0?1:-1;
            planes[i][0]=-cp*sy+sign*(i<2?-cy*horizontal:-sp*sy*vertical);
            planes[i][1]=-sp+sign*(i<2?0:cp*vertical);
            planes[i][2]=cp*cy+sign*(i<2?-sy*horizontal:sp*cy*vertical);
        }
        planes[4][0]=-cp*sy;planes[4][1]=-sp;planes[4][2]=cp*cy;
    }
    public boolean visible(double cx,double cy,double cz,double hx,double hy,double hz) {
        double dx=cx-x,dy=cy-y,dz=cz-z;
        for(int i=0;i<planes.length;i++) {
            double[] p=planes[i];
            double farthest=p[0]*dx+p[1]*dy+p[2]*dz+Math.abs(p[0])*hx+Math.abs(p[1])*hy+Math.abs(p[2])*hz;
            if(farthest<(i==4?.01:0))return false;
        }
        return true;
    }
}
