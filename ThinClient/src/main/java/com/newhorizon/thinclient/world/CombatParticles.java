package com.newhorizon.thinclient.world;

import com.newhorizon.thinclient.protocol.*;
import java.nio.ByteBuffer;
import java.util.Random;

/** Bounded server-triggered critical and sweep effects, independent from damage calculation. */
public final class CombatParticles {
    public static final class Particle {
        public double x,y,z,vx,vy,vz;
        public long born,tick;
        public int type;
        public float size;
    }
    private final Particle[] particles;
    private final Random random=new Random();
    private int next;
    public CombatParticles(int capacity) {
        if(capacity<=0)throw new IllegalArgumentException("capacity");
        particles=new Particle[capacity];for(int i=0;i<capacity;i++)particles[i]=new Particle();
    }
    public synchronized void burst(double x,double y,double z,int type,long now) {
        for(int i=0;i<(type==2?1:16);i++) {
            Particle p=particles[next];next=(next+1)%particles.length;p.type=type;p.born=p.tick=now;
            p.x=x;p.y=y;p.z=z;p.size=type==2?2:.1f;
            if(type!=2) {p.x+=random.nextDouble()-.5;p.y+=random.nextDouble()-.5;p.z+=random.nextDouble()-.5;}
            p.vx=(random.nextDouble()-.5)*.2;p.vy=random.nextDouble()*.15;p.vz=(random.nextDouble()-.5)*.2;
        }
    }
    public synchronized int snapshot(Particle[] out,long now) {
        int count=0;
        for(Particle p:particles) {
            if(p.born==0||now-p.born>=400_000_000L)continue;
            if(p.type!=2) while(now-p.tick>=50_000_000L) {
                p.x+=p.vx;p.y+=p.vy;p.z+=p.vz;p.vx*=.7;p.vy=(p.vy-.02)*.7;p.vz*=.7;p.tick+=50_000_000L;
            }
            if(count==out.length)break;
            Particle q=out[count++];q.x=p.x;q.y=p.y;q.z=p.z;q.type=p.type;q.born=p.born;q.size=p.size;
        }
        return count;
    }
    public synchronized void clear() {for(Particle p:particles)p.born=0;next=0;}
    public void readLevelParticles(ByteBuffer in,int crit,int enchanted,int sweep) throws ProtocolException {
        int type=VarInts.read(in);BinaryCodec.require(in,45);in.get();
        double x=in.getDouble(),y=in.getDouble(),z=in.getDouble();
        float dx=in.getFloat(),dy=in.getFloat(),dz=in.getFloat(),speed=in.getFloat();int count=in.getInt();
        if(!Double.isFinite(x)||!Double.isFinite(y)||!Double.isFinite(z)||!Float.isFinite(speed)||count<0)
            throw new ProtocolException("Invalid combat particle packet");
        if(type==sweep)burst(x,y,z,2,System.nanoTime());
        // Explicit server particles can also originate from commands/plugins.
        else if(type==crit||type==enchanted)burst(x,y,z,type==enchanted?1:0,System.nanoTime());
    }
}
