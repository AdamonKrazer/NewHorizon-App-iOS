package com.newhorizon.thinclient.world;

import java.nio.ByteBuffer;
import java.util.Random;
import com.newhorizon.thinclient.protocol.*;

/** Server water wakes, bubbles and bite splashes. No client-generated fish or loot timers. */
public final class FishingParticles {
    public static final int CAPACITY=192;
    public static final class Particle {public double x,y,z;public float size;public int tile;}
    private static final class State {double x,y,z,vx,vy,vz;long born,tick;int type,life;float size;}
    private final State[] pool=new State[CAPACITY];
    private final Random random=new Random();
    private int next;
    public FishingParticles(){for(int i=0;i<pool.length;i++)pool[i]=new State();}
    public synchronized void clear(){for(State p:pool)p.born=0;next=0;}
    public synchronized void read(ByteBuffer in,long now)throws ProtocolException {
        int type=VarInts.read(in);if(type!=4&&type!=27&&type!=58)return;
        BinaryCodec.require(in,45);in.get();double x=in.getDouble(),y=in.getDouble(),z=in.getDouble();
        float dx=in.getFloat(),dy=in.getFloat(),dz=in.getFloat(),speed=in.getFloat();int count=in.getInt();
        if(in.hasRemaining()||count<0||!Double.isFinite(x)||!Double.isFinite(y)||!Double.isFinite(z)
                ||!Float.isFinite(dx)||!Float.isFinite(dy)||!Float.isFinite(dz)||!Float.isFinite(speed))throw new ProtocolException("Invalid fishing particle packet");
        for(int i=0;i<Math.max(1,Math.min(CAPACITY,count));i++) {
            State p=pool[next];next=(next+1)%pool.length;p.type=type;p.born=p.tick=now;
            p.x=x+(count==0?0:random.nextGaussian()*dx);p.y=y+(count==0?0:random.nextGaussian()*dy);p.z=z+(count==0?0:random.nextGaussian()*dz);
            p.vx=count==0?dx*speed:random.nextGaussian()*speed;p.vy=count==0?dy*speed:random.nextGaussian()*speed;p.vz=count==0?dz*speed:random.nextGaussian()*speed;
            p.life=type==27||type==4?(int)(8/(random.nextDouble()*.8+.2)):8+random.nextInt(8);
            p.size=.2f+random.nextFloat()*.2f;
            if(type==4){p.vx=p.vx*.2+(random.nextDouble()*2-1)*.02;p.vy=p.vy*.2+(random.nextDouble()*2-1)*.02;p.vz=p.vz*.2+(random.nextDouble()*2-1)*.02;p.size*=random.nextFloat()*.6+.2;}
            else if(type==58)p.vy+=.1;
        }
    }
    public synchronized int snapshot(Particle[] out,long now,WorldChunkStore world) {
        int count=0;
        for(State p:pool) {
            if(p.born==0)continue;long age=(now-p.born)/50_000_000L;if(age>=p.life){p.born=0;continue;}
            if(now-p.tick>250_000_000L)p.tick=now-250_000_000L;
            while(now-p.tick>=50_000_000L) {
                p.tick+=50_000_000L;p.x+=p.vx;p.y+=p.vy;p.z+=p.vz;
                double drag=p.type==4?.85:p.type==27?.98:.98;p.vx*=drag;p.vz*=drag;
                p.vy=(p.vy+(p.type==4?.002:p.type==58?-.06:0))*drag;
            }
            if(p.type==4&&world!=null&&world.waterHeightAt((int)Math.floor(p.x),(int)Math.floor(p.y),(int)Math.floor(p.z))==0){p.born=0;continue;}
            if(count==out.length)continue;
            Particle v=out[count++];v.x=p.x;v.y=p.y;v.z=p.z;v.tile=p.type==4?43:p.type==27?44+(int)((60-p.life+age)%4):44+(int)Math.min(3,age*4/p.life);
            v.size=p.size;
        }
        return count;
    }
}
