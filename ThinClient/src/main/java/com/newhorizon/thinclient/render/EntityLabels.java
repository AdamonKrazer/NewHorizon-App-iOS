package com.newhorizon.thinclient.render;

import com.newhorizon.thinclient.world.*;
import com.newhorizon.thinclient.protocol.ChatProtocol;

/** Bounded world-space labels projected into the existing native HUD font pass. */
public final class EntityLabels {
    private final EntityTracker.Renderable[] entities=new EntityTracker.Renderable[128];
    private final double[] projected=new double[3];
    public EntityLabels(){for(int i=0;i<entities.length;i++)entities[i]=new EntityTracker.Renderable();}
    public String encode(EntityTracker tracker,PlayerListState players,ServerHudState hud,WorldChunkStore world,long now,
                         double x,double y,double z,float yaw,float pitch,float aspect,float projection,int local){
        int count=tracker.snapshotVisible(entities,now,x,y,z,48,local),emitted=0;
        StringBuilder out=new StringBuilder(2048);out.append('[');
        for(int i=0;i<count&&emitted<64;i++){
            EntityTracker.Renderable e=entities[i];String text="";double labelY=e.y+e.labelHeight+.5;
            if(e.type==101){text=e.displayText;labelY=e.y;}
            else if(e.nameVisible&&!e.customName.isEmpty()){text=e.customName;if(e.type==2)labelY=e.y+(e.appearance[15]%2==1?.9875:1.975)+.5;}
            else if(e.player&&!e.invisible){PlayerListState.Entry p=players.get(e.profileId);if(p!=null)text=hud.label(p.name,players.localName());}
            if(text.isEmpty()||text.length()>4096)continue;
            if(!project(e.x-x,labelY-y,e.z-z,yaw,pitch,aspect,projection,projected))continue;
            // Labels of hidden armor stands remain visible, but terrain still occludes them.
            if(!clearLine(world,x,y,z,e.x,labelY,e.z))continue;
            if(emitted++>0)out.append(',');out.append("{\"x\":").append(projected[0]).append(",\"y\":").append(projected[1])
                .append(",\"s\":").append(projected[2]).append(",\"text\":").append(ChatProtocol.quote(text)).append('}');
        }
        return out.append(']').toString();
    }
    static boolean project(double dx,double dy,double dz,float yaw,float pitch,float aspect,float projection,double[] out){
        if(!(aspect>0&&projection>0))return false;
        double a=Math.toRadians(-yaw),b=Math.toRadians(pitch);
        double xx=-(Math.cos(a)*dx-Math.sin(a)*dz),zz=-(Math.sin(a)*dx+Math.cos(a)*dz);
        double yy=Math.cos(b)*dy-Math.sin(b)*zz,depth=-(Math.sin(b)*dy+Math.cos(b)*zz);
        if(depth<.1||!Double.isFinite(depth))return false;
        out[0]=.5+xx*projection/aspect/depth*.5;out[1]=.5-yy*projection/depth*.5;out[2]=.025*projection/depth*.5;
        return out[0]>=-.1&&out[0]<=1.1&&out[1]>=-.1&&out[1]<=1.1;
    }
    private static boolean clearLine(WorldChunkStore world,double x,double y,double z,double tx,double ty,double tz){
        double dx=tx-x,dy=ty-y,dz=tz-z,d=Math.sqrt(dx*dx+dy*dy+dz*dz);
        int steps=Math.min(128,(int)(d*3));
        for(int i=1;i<steps;i++){double t=i/(double)steps;if(world.isSolidBlock((int)Math.floor(x+dx*t),(int)Math.floor(y+dy*t),(int)Math.floor(z+dz*t)))return false;}
        return true;
    }
}
