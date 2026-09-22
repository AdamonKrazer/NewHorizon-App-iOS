package com.newhorizon.thinclient.world;

/** Camera only: never changes the player's aim, position or packets. */
public final class PerspectiveCamera {
    public int mode;
    public double x,y,z;
    public float yaw,pitch;
    public void cycle(){mode=(mode+1)%3;}
    public void update(WorldChunkStore world,double px,double py,double pz,float lookYaw,float lookPitch){
        x=px;y=py;z=pz;yaw=lookYaw;pitch=lookPitch;
        if(mode==0)return;
        if(mode==2){yaw+=180;pitch=-pitch;}
        double yr=Math.toRadians(yaw),pr=Math.toRadians(pitch);
        double dx=Math.sin(yr)*Math.cos(pr)*4,dy=Math.sin(pr)*4,dz=-Math.cos(yr)*Math.cos(pr)*4;
        double fraction=1;
        // Vanilla tests eight offset rays so the near plane cannot cross a wall.
        for(int i=0;i<8;i++){
            double ox=((i&1)==0?-.1:.1),oy=((i&2)==0?-.1:.1),oz=((i&4)==0?-.1:.1);
            fraction=Math.min(fraction,world.projectileClip(px+ox,py+oy,pz+oz,dx,dy,dz));
        }
        fraction=Math.max(0,fraction-(fraction<1?.0125:0));
        x+=dx*fraction;y+=dy*fraction;z+=dz*fraction;
    }
}
