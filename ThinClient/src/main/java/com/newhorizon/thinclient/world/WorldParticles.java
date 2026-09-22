package com.newhorizon.thinclient.world;

import java.nio.ByteBuffer;
import java.util.Random;
import com.newhorizon.thinclient.protocol.*;

/** Fixed-capacity presentation particles. No world edits, damage or client-side loot. */
public final class WorldParticles {
    public static final int CAPACITY=512,BLOCK=2,ITEM=40;
    private static final long TICK=50_000_000L;
    public static final class Particle {
        public double x,y,z;
        public int type,data,color;
        public float size,u,v,age,life;
    }
    private static final class State {
        double x,y,z,px,py,pz,vx,vy,vz;
        float size,u,v,gravity,drag;
        int type,data,color,life;
        long born,tick;
        boolean collision;
    }
    private static final class Pending {int x,y,z,state;long expires;}
    private final State[] pool=new State[CAPACITY];
    private final Pending[] pending=new Pending[32];
    private final Random random=new Random();
    private int next,pendingNext;
    private long lastBreakTime;private int lastBreakX,lastBreakY,lastBreakZ;
    private double fallDistance;
    private long nextAmbient;
    public WorldParticles(){for(int i=0;i<pool.length;i++)pool[i]=new State();for(int i=0;i<pending.length;i++)pending[i]=new Pending();}
    public synchronized void clear(){for(State p:pool)p.born=0;for(Pending p:pending)p.expires=0;next=pendingNext=0;lastBreakTime=nextAmbient=0;fallDistance=0;}
    public synchronized void ambient(WorldChunkStore world,double cx,double cy,double cz,long now) {
        if(world==null||now<nextAmbient)return;nextAmbient=now+100_000_000L;
        for(int i=0;i<48;i++) {
            int bx=floor(cx)+random.nextInt(17)-8,by=floor(cy)+random.nextInt(9)-4,bz=floor(cz)+random.nextInt(17)-8;
            int source=AmbientParticleSources.source(world.blockStateAt(bx,by,bz)),kind=source&15,dir=source>>4;if(kind==0)continue;
            double x=bx+.5,y=by+.7,z=bz+.5;int sx=dir==3?-1:dir==4?1:0,sz=dir==1?-1:dir==2?1:0;
            if(kind>=2&&kind<=4&&dir!=0){x-=sx*.27;z-=sz*.27;y=by+.92;}
            if(kind==1)burst(44,0,x,by+1,z,3,.5,.01,0x777777,now);
            else if(kind==2||kind==3){spawn(51,0,x,y,z,0,0,0,0x777777,now);spawn(kind==2?28:33,0,x,y,z,0,0,0,0xffffff,now);}
            else if(kind==4)spawn(14,0,x,y,z,0,0,0,0xff0000,now);
            else if(kind==5||kind==6){spawn(65,0,x,by+.5,z,0,.05,0,0xffffff,now);spawn(kind==5?28:33,0,x,by+.3,z,0,.01,0,0xffffff,now);}
            else if(kind==7){x+=sx*.52;z+=sz*.52;spawn(51,0,x,by+.3,z,0,.01,0,0x777777,now);spawn(28,0,x,by+.3,z,0,0,0,0xffffff,now);}
            else if(kind==8)burst(49,0,x,y,z,4,1,.1,0x8050d0,now);
        }
    }
    private State spawn(int type,int data,double x,double y,double z,double vx,double vy,double vz,int color,long now) {
        State p=pool[next];next=(next+1)%CAPACITY;p.type=type;p.data=data;p.x=p.px=x;p.y=p.py=y;p.z=p.pz=z;
        p.vx=vx;p.vy=vy;p.vz=vz;p.born=p.tick=now;p.color=color;
        p.u=random.nextFloat()*.75f;p.v=random.nextFloat()*.75f;p.size=.1f+random.nextFloat()*.1f;
        p.life=(int)(4/(random.nextDouble()*.9+.1));p.gravity=1;p.drag=.98f;p.collision=true;
        if(type==BLOCK) {
            p.vx+=random.nextDouble()*.8-.4;p.vy+=random.nextDouble()*.8-.4;p.vz+=random.nextDouble()*.8-.4;
            double length=Math.sqrt(p.vx*p.vx+p.vy*p.vy+p.vz*p.vz),speed=(random.nextDouble()+random.nextDouble()+1)*.15*.4;
            if(length>1e-9){p.vx=p.vx/length*speed;p.vy=p.vy/length*speed+.1;p.vz=p.vz/length*speed;}
        }
        if(type!=BLOCK&&type!=ITEM) {
            p.life=20+random.nextInt(20);p.size=.2f;p.gravity=0;p.drag=.96f;p.collision=false;
            if(type==5||type==44||type==48||type==51||type==65||type==66){p.size=type==44?.5f:type==65||type==66?.6f:.25f;p.vy+=.02;p.gravity=-.1f;p.life=type==65||type==66?100+random.nextInt(100):20+random.nextInt(20);p.collision=true;}
            if(type==28||type==33||type==81){p.size=type==81?.1f:.2f;p.life=8+random.nextInt(24);}
            if(type==23){p.size=2;p.life=6+random.nextInt(4);p.vx=p.vy=p.vz=0;}
            if(type==38||type==1||type==36){p.vy+=.1;p.life=16;p.size=.3f;}
            if(type==49||type==79){p.size=.15f;p.life=40+random.nextInt(10);}
            if(type==9||type==10||type==11||type==12||type==13||type==45||type==50){p.size=.08f;p.gravity=.6f;p.collision=true;}
            if(type==14||type==15){p.size=.15f;p.life=8+random.nextInt(24);p.drag=.96f;}
            if(type==25){p.gravity=.1f;p.collision=true;}
        } else if(type==ITEM){p.gravity=1;p.size=.1f;}
        return p;
    }
    public synchronized void burst(int type,int data,double x,double y,double z,int count,double spread,double speed,int color,long now) {
        for(int i=0;i<Math.min(CAPACITY,Math.max(0,count));i++)spawn(type,data,
                x+(random.nextDouble()-.5)*spread,y+(random.nextDouble()-.5)*spread,z+(random.nextDouble()-.5)*spread,
                random.nextGaussian()*speed,random.nextGaussian()*speed,random.nextGaussian()*speed,color,now);
    }
    /** LivingEntity's food crumbs, rotated from mouth space into the player's look direction. */
    public synchronized void eat(int item,double x,double eyeY,double z,float yaw,float pitch,int count,long now) {
        if(item<=0)return;
        double cy=Math.cos(Math.toRadians(-yaw)),sy=Math.sin(Math.toRadians(-yaw));
        double cp=Math.cos(Math.toRadians(-pitch)),sp=Math.sin(Math.toRadians(-pitch));
        for(int i=0;i<Math.min(16,count);i++) {
            double px=(random.nextDouble()-.5)*.3,py=-random.nextDouble()*.6-.3,pz=.6;
            double yy=py*cp+pz*sp,zz=pz*cp-py*sp;
            double vx=(random.nextDouble()-.5)*.1,vy=random.nextDouble()*.1+.1;
            double vy2=vy*cp,vz=-vy*sp;
            spawn(ITEM,item,x+px*cy+zz*sy,eyeY+yy,z+zz*cy-px*sy,
                    vx*cy+vz*sy,vy2,vz*cy-vx*sy,0xffffff,now);
        }
    }
    public synchronized void expectBreak(int x,int y,int z,int state,long now) {
        if(state<0)return;
        for(Pending p:pending)if(p.expires>now&&p.x==x&&p.y==y&&p.z==z)return;
        Pending p=pending[pendingNext];pendingNext=(pendingNext+1)%pending.length;p.x=x;p.y=y;p.z=z;p.state=state;p.expires=now+2_000_000_000L;
    }
    public synchronized void breakBlock(int x,int y,int z,int state,long now) {
        if(state<0||state>=VanillaBlockTextures.stateCount()||BlockStatePhysics.isAir(state))return;
        if(now-lastBreakTime<250_000_000L&&lastBreakX==x&&lastBreakY==y&&lastBreakZ==z)return;
        lastBreakTime=now;lastBreakX=x;lastBreakY=y;lastBreakZ=z;
        // Vanilla ParticleEngine.destroy subdivides the outline into cells <= .25 blocks.
        int boxes=BlockStatePhysics.collisionBoxCount(state);
        if(boxes==0)boxes=1;
        int emitted=0;
        for(int box=0;box<boxes&&emitted<128;box++) {
            boolean full=BlockStatePhysics.collisionBoxCount(state)==0;
            double x0=full?0:BlockStatePhysics.collisionCoordinate(state,box,0),y0=full?0:BlockStatePhysics.collisionCoordinate(state,box,1),z0=full?0:BlockStatePhysics.collisionCoordinate(state,box,2);
            double w=full?1:Math.min(1,BlockStatePhysics.collisionCoordinate(state,box,3)-x0),h=full?1:Math.min(1,BlockStatePhysics.collisionCoordinate(state,box,4)-y0),d=full?1:Math.min(1,BlockStatePhysics.collisionCoordinate(state,box,5)-z0);
            int nx=Math.max(2,(int)Math.ceil(w/.25)),ny=Math.max(2,(int)Math.ceil(h/.25)),nz=Math.max(2,(int)Math.ceil(d/.25));
            for(int ix=0;ix<nx;ix++)for(int iy=0;iy<ny;iy++)for(int iz=0;iz<nz&&emitted<128;iz++,emitted++) {
                double fx=(ix+.5)/nx,fy=(iy+.5)/ny,fz=(iz+.5)/nz;
                spawn(BLOCK,state,x+x0+fx*w,y+y0+fy*h,z+z0+fz*d,fx-.5,fy-.5,fz-.5,0xffffff,now);
            }
        }
    }
    public synchronized void hitBlock(WorldChunkStore world,int x,int y,int z,int face,long now) {
        int state=world.blockStateAt(x,y,z);if(state<0||BlockStatePhysics.isAir(state))return;
        double px=x+.1+random.nextDouble()*.8,py=y+.1+random.nextDouble()*.8,pz=z+.1+random.nextDouble()*.8;
        if(face==0)py=y-.1;if(face==1)py=y+1.1;if(face==2)pz=z-.1;if(face==3)pz=z+1.1;if(face==4)px=x-.1;if(face==5)px=x+1.1;
        State p=spawn(BLOCK,state,px,py,pz,0,.03,0,0xffffff,now);p.size*=.6f;
    }
    public synchronized void movement(WorldChunkStore world,double x,double y,double z,double dx,double dy,double dz,
            boolean grounded,boolean sprinting,boolean flying,boolean wet,long now) {
        if(flying||wet||Math.abs(dy)>8){fallDistance=0;return;}
        if(!grounded&&dy<0)fallDistance-=dy;
        if(grounded){if(fallDistance>3)landing(world,x,y,z,fallDistance,now);fallDistance=0;}
        if(grounded&&sprinting&&dx*dx+dz*dz>1e-7)running(world,x,y,z,dx,dz,.6,now);
    }
    public synchronized void running(WorldChunkStore world,double x,double y,double z,double vx,double vz,double width,long now) {
        if(world==null)return;int state=world.blockStateAt(floor(x),floor(y-.2),floor(z));
        if(state<0||BlockStatePhysics.isAir(state)||(BlockStatePhysics.flags(state)&(BlockStatePhysics.WATER|BlockStatePhysics.LAVA))!=0)return;
        spawn(BLOCK,state,x+(random.nextDouble()-.5)*width,y+.1,z+(random.nextDouble()-.5)*width,-vx*4,1.5,-vz*4,0xffffff,now);
    }
    private void landing(WorldChunkStore world,double x,double y,double z,double distance,long now) {
        int state=world.blockStateAt(floor(x),floor(y-.2),floor(z));if(state<0||BlockStatePhysics.isAir(state))return;
        double strength=Math.min(.2+Math.ceil(distance-3)/15,2.5);
        burst(BLOCK,state,x,y+.1,z,(int)(150*strength),0,.15,0xffffff,now);
    }
    public synchronized void read(ByteBuffer in,long now)throws ProtocolException {
        int type=VarInts.read(in);BinaryCodec.require(in,45);in.get();
        double x=in.getDouble(),y=in.getDouble(),z=in.getDouble();float dx=in.getFloat(),dy=in.getFloat(),dz=in.getFloat(),speed=in.getFloat();int count=in.getInt();
        if(!Double.isFinite(x)||!Double.isFinite(y)||!Double.isFinite(z)||!Float.isFinite(dx)||!Float.isFinite(dy)||!Float.isFinite(dz)||!Float.isFinite(speed)||count<0)throw new ProtocolException("Invalid world particle packet");
        int data=0,color=0xffffff;float scale=1;
        if(type==2||type==3||type==25){data=VarInts.read(in);if(data<0||data>=VanillaBlockTextures.stateCount())throw new ProtocolException("Invalid particle block state");if(type==3)return;}
        else if(type==40){BinaryCodec.require(in,1);if(in.get()==0)return;data=VarInts.read(in);BinaryCodec.require(in,1);in.get();NbtSkipper.skipRoot(in);if(data<0||data>=1255)throw new ProtocolException("Invalid particle item");}
        else if(type==42||type==43){data=type==42?886:872;type=ITEM;}
        else if(type==14||type==15){BinaryCodec.require(in,type==14?16:28);float r=in.getFloat(),g=in.getFloat(),b=in.getFloat();scale=in.getFloat();if(!Float.isFinite(r)||!Float.isFinite(g)||!Float.isFinite(b)||!Float.isFinite(scale))throw new ProtocolException("Invalid dust particle");color=rgb(r,g,b);scale=Math.max(.01f,Math.min(4,scale));if(type==15){for(int i=0;i<3;i++)if(!Float.isFinite(in.getFloat()))throw new ProtocolException("Invalid dust transition");}}
        else if(!ParticleSprites.supports(type)&&type!=22)return;
        if(in.hasRemaining())throw new ProtocolException("Trailing particle parameters");
        if(type==22){burst(23,0,x,y,z,8,4,0,0xffffff,now);return;}
        // Already handled by dedicated fishing/combat/firework pools.
        for(int i=0;i<Math.min(CAPACITY,Math.max(1,count));i++) {
            double vx=count==0?dx*speed:random.nextGaussian()*speed,vy=count==0?dy*speed:random.nextGaussian()*speed,vz=count==0?dz*speed:random.nextGaussian()*speed;
            int tint=color;if((type==0||type==21)&&count==0){tint=rgb(dx,dy,dz);vx=vy=vz=0;}
            State p=spawn(type,data,x+(count==0?0:random.nextGaussian()*dx),y+(count==0?0:random.nextGaussian()*dy),z+(count==0?0:random.nextGaussian()*dz),vx,vy,vz,tint,now);p.size*=scale;
        }
    }
    public synchronized void levelEvent(ByteBuffer in,long now)throws ProtocolException {
        BinaryCodec.require(in,17);int event=in.getInt();long pos=in.getLong();int data=in.getInt();in.get();
        if(in.hasRemaining())throw new ProtocolException("Trailing visual level event");
        int x=(int)(pos>>38),y=(int)(pos<<52>>52),z=(int)(pos<<26>>38);
        if(event==2001)breakBlock(x,y,z,data,now);
        else if(event==2002||event==2007){burst(ITEM,958,x+.5,y+.5,z+.5,8,0,.15,0xffffff,now);burst(event==2007?39:16,0,x+.5,y+.5,z+.5,100,1,.08,data,now);}
        else if(event==2000)burst(51,0,x+.5,y+.5,z+.5,10,.2,.02,0x888888,now);
        else if(event==2004){burst(51,0,x+.5,y+.5,z+.5,20,2,.02,0xffffff,now);burst(28,0,x+.5,y+.5,z+.5,20,2,.02,0xffffff,now);}
        else if(event==2006)burst(8,0,x+.5,y+.5,z+.5,80,2,.05,0xc04bea,now);
    }
    public synchronized int snapshot(Particle[] out,long now,WorldChunkStore world,double cx,double cy,double cz) {
        if(world!=null)for(Pending p:pending) {
            if(p.expires==0)continue;if(now>p.expires){p.expires=0;continue;}
            int state=world.blockStateAt(p.x,p.y,p.z);if(state<0||state==p.state)continue;p.expires=0;
            if(BlockStatePhysics.isAir(state)||(BlockStatePhysics.flags(state)&(BlockStatePhysics.WATER|BlockStatePhysics.LAVA))!=0)breakBlock(p.x,p.y,p.z,p.state,now);
        }
        int count=0;
        for(State p:pool) {
            if(p.born==0)continue;
            if(now-p.born>=p.life*TICK){p.born=0;continue;}
            if(now-p.tick>5*TICK)p.tick=now-5*TICK;
            while(now-p.tick>=TICK) {
                p.tick+=TICK;p.px=p.x;p.py=p.y;p.pz=p.z;p.vy-=.04*p.gravity;
                double dx=p.vx,dy=p.vy,dz=p.vz;
                if(p.collision&&world!=null){double hit=world.projectileClip(p.x,p.y,p.z,dx,dy,dz);if(hit<1){dx*=hit;dy*=hit;dz*=hit;p.vx*=.7;p.vz*=.7;p.vy=0;}}
                p.x+=dx;p.y+=dy;p.z+=dz;p.vx*=p.drag;p.vy*=p.drag;p.vz*=p.drag;
            }
            if(count==out.length)continue;
            double dx=p.x-cx,dy=p.y-cy,dz=p.z-cz;if(dx*dx+dy*dy+dz*dz>4096)continue;
            float partial=Math.max(0,Math.min(1,(now-p.tick)/(float)TICK));Particle q=out[count++];
            q.x=p.px+(p.x-p.px)*partial;q.y=p.py+(p.y-p.py)*partial;q.z=p.pz+(p.z-p.pz)*partial;
            q.type=p.type;q.data=p.data;q.color=p.color;q.size=p.size;q.u=p.u;q.v=p.v;q.age=(now-p.born)/(float)TICK;q.life=p.life;
        }
        return count;
    }
    private static int rgb(float r,float g,float b){return ((int)(Math.max(0,Math.min(1,r))*255)<<16)|((int)(Math.max(0,Math.min(1,g))*255)<<8)|(int)(Math.max(0,Math.min(1,b))*255);}
    private static int floor(double v){return (int)Math.floor(v);}
}
