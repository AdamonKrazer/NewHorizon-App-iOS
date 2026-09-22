package com.newhorizon.thinclient.world;

import com.newhorizon.thinclient.audio.SoundEventQueue;
import java.util.Random;

/** Vanilla firework shapes and timing in fixed pools. Status 17 owns explosions. */
public final class FireworkEffects {
    public static final int CAPACITY=2048;
    private static final long TICK=50_000_000L;
    private static final double[][] STAR={{0,1},{.3455,.309},{.9511,.309},{.3795918367346939,-.12653061224489795},{.6122448979591837,-.8040816326530612},{0,-.35918367346938773}};
    private static final double[][] CREEPER={{0,.2},{.2,.2},{.2,.6},{.6,.6},{.6,.2},{.2,.2},{.2,0},{.4,0},{.4,-.6},{.2,-.6},{.2,-.4},{0,-.4}};
    private final Spark[] sparks=new Spark[CAPACITY];
    private final Starter[] starters=new Starter[16];
    private final PendingSound[] pending=new PendingSound[32];
    private final Random random=new Random();
    private SoundEventQueue sounds;
    private long tickTime;
    private int cursor,activeCount;
    private double cameraX,cameraY,cameraZ;
    public FireworkEffects() {
        for(int i=0;i<sparks.length;i++)sparks[i]=new Spark();
        for(int i=0;i<starters.length;i++)starters[i]=new Starter();
        for(int i=0;i<pending.length;i++)pending[i]=new PendingSound();
    }
    public synchronized void setSounds(SoundEventQueue sounds) { this.sounds=sounds; }
    public synchronized void clear() {
        for(Spark p:sparks)p.life=0;for(Starter s:starters)s.data=null;
        for(PendingSound s:pending)s.name=null;tickTime=0;cursor=activeCount=0;
    }
    synchronized void explode(ProjectileMotion rocket,long now) {
        if(rocket.exploded)return;
        rocket.exploded=true;
        if(tickTime==0)tickTime=now;
        if(rocket.firework==null||rocket.firework.count==0) {
            for(int i=0,n=2+random.nextInt(3);i<n;i++) {
                Spark p=spawn(rocket.x,rocket.y,rocket.z,random.nextGaussian()*.05,.005,random.nextGaussian()*.05);
                if(p!=null){p.kind=2;p.life=8+random.nextInt(8);p.r=p.g=p.b=.8f;}
            }
            return;
        }
        for(Starter s:starters)if(s.data==null) {
            s.data=rocket.firework;s.x=rocket.x;s.y=rocket.y;s.z=rocket.z;
            s.vx=rocket.vx;s.vy=rocket.vy;s.vz=rocket.vz;s.born=now;s.next=0;
            s.flicker=false;s.large=s.data.count>=3;
            for(int i=0;i<s.data.count;i++) {s.flicker|=s.data.explosions[i].flicker;s.large|=s.data.explosions[i].type==1;}
            return;
        }
    }
    synchronized void trail(ProjectileMotion rocket,long now) {
        if(rocket.exploded||now-rocket.trailTime<TICK)return;
        rocket.trailTime=now;if(tickTime==0)tickTime=now;
        spawn(rocket.x,rocket.y,rocket.z,random.nextGaussian()*.05,-rocket.vy*.5,random.nextGaussian()*.05);
    }
    public static final class Particle {
        public double x,y,z;
        public float size,alpha;
        public int color,tile;
    }
    private static final class Spark {
        double x,y,z,px,py,pz,vx,vy,vz;
        float r,g,b,size;
        int life,age,fade,kind;
        boolean trail,flicker;
    }
    private static final class Starter {
        FireworkData data;double x,y,z,vx,vy,vz;long born;int next;boolean flicker,large;
    }
    private static final class PendingSound {String name;double x,y,z;long at;float pitch;}
    private Spark spawn(double x,double y,double z,double vx,double vy,double vz) {
        if(activeCount==CAPACITY)return null;
        for(int i=0;i<sparks.length;i++) {
            Spark p=sparks[cursor];cursor=(cursor+1)%sparks.length;
            if(p.life!=0)continue;
            p.x=p.px=x;p.y=p.py=y;p.z=p.pz=z;p.vx=vx;p.vy=vy;p.vz=vz;
            activeCount++;p.age=p.kind=0;p.life=48+random.nextInt(12);p.fade=-1;
            p.r=p.g=p.b=1;p.trail=p.flicker=false;p.size=.15f+random.nextFloat()*.15f;
            return p;
        }
        return null; // Drop excess visual detail; never grow memory or gameplay state.
    }
    private void spark(Starter s,FireworkData.Explosion e,double vx,double vy,double vz) {
        Spark p=spawn(s.x,s.y,s.z,vx,vy,vz);if(p==null)return;
        int c=e.color(random);p.r=(c>>16&255)/255f;p.g=(c>>8&255)/255f;p.b=(c&255)/255f;
        p.fade=e.fadeCount==0?-1:e.fade[random.nextInt(e.fadeCount)];p.trail=e.trail;p.flicker=e.flicker;
    }
    private void burst(Starter s,FireworkData.Explosion e) {
        if(e.type==0||e.type==1) {
            int n=e.type==1?4:2;double speed=e.type==1?.5:.25;
            for(int y=-n;y<=n;y++)for(int x=-n;x<=n;x++)for(int z=-n;z<=n;z++) {
                double dx=x+(random.nextDouble()-random.nextDouble())*.5;
                double dy=y+(random.nextDouble()-random.nextDouble())*.5;
                double dz=z+(random.nextDouble()-random.nextDouble())*.5;
                double length=Math.sqrt(dx*dx+dy*dy+dz*dz)/speed+random.nextGaussian()*.05;
                if(Math.abs(length)>1e-9)spark(s,e,dx/length,dy/length,dz/length);
                if(y!=-n&&y!=n&&x!=-n&&x!=n)z+=n*2-1;
            }
        } else if(e.type==2||e.type==3) {
            double[][] shape=e.type==2?STAR:CREEPER;double rotation=random.nextFloat()*Math.PI;
            spark(s,e,shape[0][0]*.5,shape[0][1]*.5,0);
            for(int plane=0;plane<3;plane++) {
                double angle=rotation+plane*Math.PI*(e.type==3?.034:.34);
                for(int i=1;i<shape.length;i++)for(int step=1;step<=4;step++) {
                    double t=step*.25,dx=(shape[i-1][0]+(shape[i][0]-shape[i-1][0])*t)*.5;
                    double dy=(shape[i-1][1]+(shape[i][1]-shape[i-1][1])*t)*.5;
                    for(int side=-1;side<=1;side+=2)spark(s,e,dx*Math.cos(angle)*side,dy,dx*Math.sin(angle)*side);
                }
            }
        } else {
            double dx=random.nextGaussian()*.05,dz=random.nextGaussian()*.05;
            for(int i=0;i<70;i++)spark(s,e,s.vx*.5+random.nextGaussian()*.15+dx,
                    s.vy*.5+random.nextDouble()*.5,s.vz*.5+random.nextGaussian()*.15+dz);
        }
        Spark flash=spawn(s.x,s.y,s.z,0,0,0);
        if(flash!=null) {flash.kind=1;flash.life=4;int c=e.colorCount==0?0x1e1b1b:e.colors[0];flash.r=(c>>16&255)/255f;flash.g=(c>>8&255)/255f;flash.b=(c&255)/255f;}
    }
    private void sound(Starter s,String name,float pitch,long now) {
        double dx=s.x-cameraX,dy=s.y-cameraY,dz=s.z-cameraZ,distance=Math.sqrt(dx*dx+dy*dy+dz*dz);
        if(distance>=16)name+="_far";
        for(PendingSound p:pending)if(p.name==null) {
            p.name="minecraft:entity.firework_rocket."+name;p.x=s.x;p.y=s.y;p.z=s.z;p.pitch=pitch;
            p.at=now+(distance>10?(long)(distance/2)*TICK:0);return;
        }
    }
    public synchronized int snapshot(Particle[] out,long now,double x,double y,double z) {
        cameraX=x;cameraY=y;cameraZ=z;
        if(tickTime==0)tickTime=now;
        // A resumed/background frame expires old effects rather than simulating seconds of work.
        if(now-tickTime>5*TICK) {
            long missed=(now-tickTime)/TICK-5;
            for(Spark p:sparks)if(p.life>0) {p.age+=(int)Math.min(1000,missed);if(p.age>=p.life){p.life=0;activeCount--;}}
            tickTime=now-5*TICK;
        }
        while(now-tickTime>=TICK) { tickTime+=TICK;step(); }
        for(Starter s:starters)if(s.data!=null) {
            int age=(int)Math.min(10000,(now-s.born)/TICK);
            if(age>80) {s.data=null;continue;}
            if(s.next==0)sound(s,s.large?"large_blast":"blast",.95f+random.nextFloat()*.1f,now);
            while(s.next<s.data.count&&age>=s.next*2)burst(s,s.data.explosions[s.next++]);
            if(age>=s.data.count*2+(s.flicker?15:0)) {
                if(s.flicker)sound(s,"twinkle",.9f+random.nextFloat()*.15f,now);
                s.data=null;
            }
        }
        for(PendingSound p:pending)if(p.name!=null&&now>=p.at) {
            if(sounds!=null)sounds.play(p.name,SoundEventQueue.AMBIENT,p.x,p.y,p.z,20,p.pitch,random.nextLong());p.name=null;
        }
        int count=0;double t=Math.max(0,Math.min(1,(now-tickTime)/(double)TICK));
        for(Spark p:sparks) {
            if(p.life==0||count==out.length)continue;
            if(p.flicker&&p.age>=p.life/3&&(p.age+p.life)/3%2!=0)continue;
            double dx=p.x-x,dy=p.y-y,dz=p.z-z;if(dx*dx+dy*dy+dz*dz>192*192)continue;
            Particle v=out[count++];v.x=p.px+(p.x-p.px)*t;v.y=p.py+(p.y-p.py)*t;v.z=p.pz+(p.z-p.pz)*t;
            v.alpha=p.age<=p.life/2?.99f:Math.max(0,1-(p.age-p.life/2f)/p.life);
            v.size=p.size;v.tile=24+Math.min(7,p.age*7/p.life);
            if(p.kind==1) {v.tile=32;v.size=(float)(7.1*Math.sin((p.age+t-1)*.25*Math.PI));v.alpha=(float)(.6-(p.age+t-1)*.125);}
            else if(p.kind==2) {v.tile=33+Math.min(7,p.age*7/p.life);v.size=.3f;}
            v.color=(Math.round(p.r*255)<<16)|(Math.round(p.g*255)<<8)|Math.round(p.b*255);
        }
        return count;
    }
    private void step() {
        // Capture the active generation so children do not advance again in their birth tick.
        for(Spark p:sparks)if(p.life>0) {
            p.age++;p.px=p.x;p.py=p.y;p.pz=p.z;
            if(p.age>=p.life) {p.life=0;activeCount--;continue;}
            if(p.kind==1)continue;
            p.vy-=.004;p.x+=p.vx;p.y+=p.vy;p.z+=p.vz;p.vx*=.91;p.vy*=.91;p.vz*=.91;
            if(p.age>p.life/2&&p.fade>=0) {p.r+=((p.fade>>16&255)/255f-p.r)*.2f;p.g+=((p.fade>>8&255)/255f-p.g)*.2f;p.b+=((p.fade&255)/255f-p.b)*.2f;}
        }
        for(Spark p:sparks)if(p.life>0&&p.trail&&p.age<p.life/2&&(p.age+p.life)%2==0) {
            Spark child=spawn(p.x,p.y,p.z,0,0,0);
            if(child!=null) {child.age=child.life/2;child.r=p.r;child.g=p.g;child.b=p.b;child.fade=p.fade;child.flicker=p.flicker;}
        }
    }
}
