package com.newhorizon.thinclient.world;

import java.nio.ByteBuffer;
import com.newhorizon.thinclient.protocol.*;
import com.newhorizon.thinclient.memory.MemoryBudget;

public final class WorldParticlesSelfTest {
    private static final long T=1_000_000_000L;
    private static final WorldParticles.Particle[] OUT=new WorldParticles.Particle[512];
    static {for(int i=0;i<OUT.length;i++)OUT[i]=new WorldParticles.Particle();}
    public static void run()throws Exception {
        WorldParticles p=new WorldParticles();int stone=VanillaBlockTextures.defaultState("stone");
        p.breakBlock(0,0,0,stone,T);check(p.snapshot(OUT,T,null,0,0,0)==64,"full block creates 4x4x4 debris");
        for(int i=0;i<64;i++)check(OUT[i].type==2&&OUT[i].data==stone&&OUT[i].u>=0&&OUT[i].u<=.75&&OUT[i].v<=.75,"block texture quarter sampling");
        p.breakBlock(0,0,0,stone,T+10);check(p.snapshot(OUT,T+10,null,0,0,0)==64,"local/server break deduplication");
        p.clear();p.read(packet(2,100000,stone),T);check(p.snapshot(OUT,T,null,0,0,0)==512,"server particle budget");
        check(p.snapshot(OUT,T+30_000_000_000L,null,0,0,0)==0,"expired storm has bounded catch-up");
        p.clear();p.read(packet(40,0,887),T);check(p.snapshot(OUT,T,null,0,0,0)==1&&OUT[0].data==887,"item parameters and directional count zero");
        p.clear();p.read(packet(51,0,0),T);p.snapshot(OUT,T+100_000_000L,null,0,0,0);check(OUT[0].z>0,"directional smoke velocity");
        boolean bad=false;ByteBuffer truncated=packet(2,1,stone);truncated.limit(truncated.limit()-1);try{p.read(truncated,T);}catch(ProtocolException expected){bad=true;}check(bad,"truncated block parameters rejected");
        bad=false;try{p.read(packet(51,-1,0),T);}catch(ProtocolException expected){bad=true;}check(bad,"negative particle count rejected");
        int supported=0;for(int i=0;i<95;i++)if(ParticleSprites.supports(i)){supported++;check(ParticleSprites.frame(i,100,20)>=0&&ParticleSprites.frame(i,100,20)<148,"original frame bounds");}
        check(supported==76,"native particle definition coverage");
        check(AmbientParticleSources.source(VanillaBlockTextures.defaultState("furnace"))==0,"unlit furnace has no smoke");
        check((AmbientParticleSources.source(VanillaBlockTextures.defaultState("torch"))&15)==2,"torch source from original block states");
        blockConfirmationAndMotion();entityEvents();
        System.out.println("World effects tests passed: block confirmations/debris, packet bounds/item payloads, collision/landing/sprint, projectile impact, fire metadata and 76 sprite sequences");
    }
    private static void blockConfirmationAndMotion()throws Exception {
        WorldChunkStore world=new WorldChunkStore(MemoryBudget.lowRamDefaults(),1,16,-1);world.handle(new WorldMessage.Reset("minecraft:overworld",0,16));
        ByteBuffer chunk=ByteBuffer.allocate(16);chunk.putShort((short)0).put((byte)0).put((byte)0).put((byte)0).put((byte)0).put((byte)0).put((byte)0).flip();world.applyVanillaChunkData(0,0,chunk);
        int stone=VanillaBlockTextures.defaultState("stone");world.applyVanillaBlockUpdate(2,1,2,stone);
        WorldParticles p=new WorldParticles();p.expectBreak(2,1,2,stone,T);check(p.snapshot(OUT,T+100,world,2,1,2)==0,"no debris before server accepts break");
        world.applyVanillaBlockUpdate(2,1,2,0);check(p.snapshot(OUT,T+1000,world,2,1,2)==64,"accepted local break creates original-state debris");
        p.clear();world.applyVanillaBlockUpdate(2,0,2,stone);
        p.movement(world,2.5,1,2.5,.1,0,0,true,true,false,false,T);check(p.snapshot(OUT,T,world,2,1,2)==1,"sprinting foot dust");
        p.snapshot(OUT,T+100_000_000L,world,2,1,2);check(OUT[0].y<1.6,"sprint input velocity normalized like vanilla, not launched skyward");
        p.clear();p.movement(world,2.5,1,2.5,0,-4,0,false,false,false,false,T);
        p.movement(world,2.5,1,2.5,0,0,0,true,false,false,false,T+50_000_000L);int landing=p.snapshot(OUT,T+50_000_000L,world,2,1,2);check(landing>=30&&landing<100,"landing dust scales with fall distance");
        p.clear();p.movement(world,2.5,1,2.5,0,-6,0,false,false,false,true,T);p.movement(world,2.5,1,2.5,0,0,0,true,false,false,false,T+1);check(p.snapshot(OUT,T+1,world,2,1,2)==0,"water resets fall emitter");
        p.clear();p.movement(world,2.5,1,2.5,0,-6,0,false,false,true,false,T);p.movement(world,2.5,1,2.5,0,0,0,true,false,false,false,T+1);check(p.snapshot(OUT,T+1,world,2,1,2)==0,"flight resets fall emitter");
        world.close();
    }
    private static void entityEvents()throws Exception {
        EntityTracker tracker=new EntityTracker(1);java.lang.reflect.Method put=EntityTracker.class.getDeclaredMethod("put",int.class,int.class,double.class,double.class,double.class,float.class,float.class,float.class,boolean.class);put.setAccessible(true);
        put.invoke(tracker,1,ProjectileKind.EGG,0d,1d,0d,0f,0f,0f,false);long now=System.nanoTime();tracker.status(1,3,now);
        check(tracker.worldParticles(OUT,now,0,1,0)==8&&OUT[0].data==887,"egg entity event emits eight original item fragments");
        tracker.clear();put.invoke(tracker,2,122,0d,1d,0d,0f,0f,0f,true);
        tracker.setMetadata(ByteBuffer.wrap(new byte[]{2,0,0,1,(byte)255}));EntityTracker.Renderable[] out={new EntityTracker.Renderable()};tracker.snapshotVisible(out,System.nanoTime(),0,1,0,20,-1);check(out[0].burning,"player metadata enables flame geometry");
        tracker.setMetadata(ByteBuffer.wrap(new byte[]{2,0,0,0,(byte)255}));tracker.snapshotVisible(out,System.nanoTime(),0,1,0,20,-1);check(!out[0].burning,"extinguishing metadata stops flame geometry");
    }
    private static ByteBuffer packet(int type,int count,int data){ByteBuffer b=ByteBuffer.allocate(96);VarInts.write(b,type);b.put((byte)0).putDouble(0).putDouble(1).putDouble(0).putFloat(0).putFloat(0).putFloat(.1f).putFloat(1).putInt(count);if(type==2)VarInts.write(b,data);if(type==40){b.put((byte)1);VarInts.write(b,data);b.put((byte)1).put((byte)0);}b.flip();return b;}
    private static void check(boolean ok,String label){if(!ok)throw new AssertionError(label);}
}
