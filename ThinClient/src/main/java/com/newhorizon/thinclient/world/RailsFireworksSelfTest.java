package com.newhorizon.thinclient.world;

import com.newhorizon.thinclient.protocol.*;
import com.newhorizon.thinclient.render.SurfaceMesher;
import com.newhorizon.thinclient.audio.SoundEventQueue;
import com.newhorizon.thinclient.memory.MemoryBudget;
import java.nio.ByteBuffer;
import java.util.UUID;

public final class RailsFireworksSelfTest {
    private static final long T=50_000_000L;
    public static void run() throws Exception {
        check(ProjectileKind.blockUseItem("minecraft:firework_rocket")&&ProjectileKind.blockUseItem("CHEST_MINECART")
                &&ProjectileKind.blockUseItem("POWERED_RAIL")&&!ProjectileKind.blockUseItem("mod:rail"),"block-use priority before offhand shield");
        rails();metadata();effects();
        System.out.println("Rails/fireworks tests passed: 92 states, mesh, picking, slopes, NBT, flight, status 17, shapes, sounds, capacity, reset");
    }
    private static void rails() throws Exception {
        SurfaceChunk chunk=new SurfaceChunk(ByteBuffer.allocate(8),ByteBuffer.allocate(SurfaceChunk.FULL_BLOCK_BYTES),
                ByteBuffer.allocate(0),ByteBuffer.allocate(0),ByteBuffer.allocate(0));
        chunk.reset(-1,-2);chunk.finishFullBlockUpdate(16);
        ByteBuffer mesh=ByteBuffer.allocate(8192);int states=0;
        for(int state=0;state<24135;state++)if(RailState.isRail(state)) {
            states++;chunk.putFullBlockState(2,2,3,state);int vertices=SurfaceMesher.mesh(chunk,0,mesh);
            int railVertices=0;float min=Float.MAX_VALUE,max=-Float.MAX_VALUE;
            for(int i=0;i<vertices;i++)if((mesh.get(i*16+15)&255)==3) {
                railVertices++;float y=mesh.getFloat(i*16+4);min=Math.min(min,y);max=Math.max(max,y);
                check((mesh.get(i*16+14)&255)<8,"valid rail atlas tile");
                check(mesh.getFloat(i*16)>=-14&&mesh.getFloat(i*16)<=-13,"negative chunk rail coordinates");
            }
            int shape=RailState.shape(state);boolean slope=shape>=2&&shape<=5;
            check(railVertices==12&&min==2.0625f&&max==(slope?3.0625f:2.0625f),"two-sided rail planes and slope height: "+state);
            boolean wet=(BlockStatePhysics.flags(state)&BlockStatePhysics.WATER)!=0;
            check(wet?vertices>12:vertices==12,"rails do not emit generic cubes; waterlogged rails retain water");
            check(!BlockStatePhysics.intersectsCollision(state,0,0,0,0,0,0,1,1,1),"rails have no walking collision");
        }
        check(states==92&&!RailState.isRail(-1)&&!RailState.isRail(65535),"exact vanilla rail state domain");
        chunk.putFullBlockState(2,2,3,4663);
        check(SurfaceMesher.mesh(chunk,0,ByteBuffer.allocate(191))==0&&SurfaceMesher.lastMeshWasTruncated(),"rail emits atomically under VBO pressure");
        check(RailState.hit(4663,.5,1,.5,0,-1,0,0,0,0,5)==.875,"flat rail top is 1/8 block");
        check(RailState.hit(4663,-1,.3,.5,1,0,0,0,0,0,5)<0,"ray above flat rail passes through");
        check(RailState.hit(4667,.5,1,.5,0,-1,0,0,0,0,5)==.375,"ascending outline is 5/8 block");
        WorldChunkStore world=new WorldChunkStore(MemoryBudget.lowRamDefaults(),2,16);
        world.handle(new WorldMessage.Reset("minecraft:overworld",0,16));
        ByteBuffer air=ByteBuffer.allocate(20);air.putShort((short)0).put((byte)0);VarInts.write(air,0);VarInts.write(air,0);
        air.put((byte)0);VarInts.write(air,0);VarInts.write(air,0);air.flip();world.applyVanillaChunkData(0,0,air);
        world.applyVanillaBlockUpdate(3,0,3,4662);
        check(world.isTargetableBlock(3,0,3),"waterlogged rail remains targetable");
        double[] point=new double[3];
        world.applyVanillaBlockUpdate(3,0,3,4667); // ascending east, dry
        check(RailPath.project(world,3.25,.6,3.8,point)&&Math.abs(point[0]-3.25)<1e-7
                &&Math.abs(point[1]-.3125)<1e-7&&point[2]==3.5,"minecart projects to ascending centerline");
        world.applyVanillaBlockUpdate(3,0,3,4675); // south east
        check(RailPath.project(world,3.8,.1,3.8,point)&&Math.abs(point[0]-3.75)<1e-7&&Math.abs(point[2]-3.75)<1e-7,"curve centerline matches vanilla diagonal");
        world.applyVanillaBlockUpdate(3,0,3,4667);
        EntityTracker.Renderable cart=new EntityTracker.Renderable();cart.x=3.5f;cart.y=.6f;cart.z=3.5f;
        check(RailPath.pose(world,cart,new double[3],new double[3],new double[3])&&Math.abs(cart.pitch)>40&&Math.abs(cart.pitch)<46,"cart body tilts on ramp");
        world.applyVanillaBlockUpdate(3,0,3,0);check(!world.isTargetableBlock(3,0,3)&&!RailPath.project(world,3.5,.6,3.5,point),"removed rails stop projection and selection");
        world.close();
    }
    private static ByteBuffer tag(int type,int explosions,int colors,boolean trail,boolean flicker) throws Exception {
        ByteBuffer b=ByteBuffer.allocate(65536);b.put((byte)10).putShort((short)0);named(b,10,"Fireworks");
        named(b,1,"Flight");b.put((byte)3);named(b,9,"Explosions");b.put((byte)10).putInt(explosions);
        for(int n=0;n<explosions;n++) {
            named(b,1,"Type");b.put((byte)type);named(b,1,"Trail");b.put((byte)(trail?1:0));named(b,1,"Flicker");b.put((byte)(flicker?1:0));
            named(b,11,"Colors");b.putInt(colors);for(int c=0;c<colors;c++)b.putInt(0xff0000);
            named(b,11,"FadeColors");b.putInt(1).putInt(0x0000ff);b.put((byte)0);
        }
        b.put((byte)0).put((byte)0).flip();return b;
    }
    private static void named(ByteBuffer b,int type,String name) throws Exception {byte[] bytes=name.getBytes("UTF-8");b.put((byte)type).putShort((short)bytes.length).put(bytes);}
    private static void metadata() throws Exception {
        FireworkData emptyColors=FireworkData.read(tag(0,1,0,false,false));
        check(emptyColors.explosions[0].color(new java.util.Random())==1973019,"vanilla black firework color fallback");
        FireworkData d=FireworkData.read(tag(3,1,2,true,true));
        check(d.flight==3&&d.count==1&&d.explosions[0].type==3&&d.explosions[0].trail&&d.explosions[0].flicker
                &&d.explosions[0].colors[0]==0xff0000&&d.explosions[0].fade[0]==255,"item NBT effects decoded");
        d=FireworkData.read(tag(99,20,64,false,false));
        check(d.count==8&&d.explosions[0].colorCount==16&&d.explosions[0].type==0,"oversized visual details capped and unknown type falls back");
        ByteBuffer truncated=tag(0,1,1,false,false);truncated.limit(truncated.limit()-2);boolean rejected=false;
        try{FireworkData.read(truncated);}catch(ProtocolException expected){rejected=true;}check(rejected,"truncated firework NBT rejected");
        ProjectileMotion p=new ProjectileMotion();p.reset(37,0,0,0,0,0,.1,.05,0,0);p.advance(2*T,null);
        check(Math.abs(p.vx-.13225)<1e-8&&Math.abs(p.y-.22)<1e-8,"20 Hz ascending rocket acceleration");
        p.reset(37,0,0,0,0,0,1,.2,0,0);
        ByteBuffer b=ByteBuffer.allocate(65536);b.put((byte)8);VarInts.write(b,7);b.put((byte)1);VarInts.write(b,1066);b.put((byte)1).put(tag(2,1,1,false,false));
        b.put((byte)9);VarInts.write(b,19);VarInts.write(b,0);b.put((byte)10);VarInts.write(b,8);b.put((byte)1).put((byte)255).flip();ProjectileMetadata.read(b,p);
        p.advance(2*T,null);check(p.shotAtAngle&&p.attachedEntity==-1&&p.x==2&&Math.abs(p.y-.4)<1e-8&&p.firework.explosions[0].type==2,"crossbow rocket keeps angled velocity and item data");
        p.attachedEntity=7;p.advance(3*T,null);check(p.x==2,"attached rocket does not simulate free flight");
        p.reset(24,0,0,0,0,0,0,0,0,0);check(p.firework==null&&!p.shotAtAngle&&p.attachedEntity==-1&&!p.exploded,"entity slot reuse clears rocket fields");
    }
    private static void effects() throws Exception {
        FireworkEffects f=new FireworkEffects();SoundEventQueue sounds=new SoundEventQueue(64);f.setSounds(sounds);
        FireworkEffects.Particle[] out=new FireworkEffects.Particle[2048];for(int i=0;i<out.length;i++)out[i]=new FireworkEffects.Particle();
        int[] expected={99,387,122,266,71};
        for(int type=0;type<5;type++) {
            f.clear();sounds.clear();ProjectileMotion p=rocket(type,false,false);f.explode(p,T);
            int count=f.snapshot(out,T,0,0,0);check(count==expected[type],"vanilla shape particle count "+type+": "+count);
            for(int i=0;i<count;i++)check(out[i].color==0xff0000,"explosion uses item color");
            f.explode(p,T);check(f.snapshot(out,T,0,0,0)==count&&sounds.size()==1,"duplicate status cannot replay explosion");
            f.snapshot(out,33*T,0,0,0);check(f.snapshot(out,100*T,0,0,0)==0,"effects expire after background pause");
        }
        f.clear();sounds.clear();ProjectileMotion p=rocket(0,true,true);f.explode(p,T);int start=f.snapshot(out,T,0,0,0);
        int most=start;boolean fades=false,flickers=false;
        for(int age=1;age<62;age++) {
            int count=f.snapshot(out,(age+1)*T,0,0,0);most=Math.max(most,count);
            for(int i=0;i<count;i++) {fades|=(out[i].color&255)>0;flickers|=out[i].alpha<.99f;}
        }
        check(most>start&&fades&&flickers&&sounds.size()==2,"trail children, fade color, alpha and twinkle");
        f.clear();sounds.clear();p=rocket(0,false,false);p.firework=null;f.explode(p,T);
        int count=f.snapshot(out,T,0,0,0);check(count>=2&&count<=4&&sounds.size()==0&&out[0].tile>=33,"rocket without stars makes only poof");
        f.clear();sounds.clear();p=rocket(1,false,false);p.x=40;f.explode(p,T);f.snapshot(out,T,0,0,0);
        check(sounds.size()==0,"distant sound is delayed");f.snapshot(out,21*T,0,0,0);SoundEventQueue.Event sound=new SoundEventQueue.Event();
        check(sounds.poll(sound)&&sound.soundName.equals("minecraft:entity.firework_rocket.large_blast_far"),"far blast variant and distance delay");
        f.clear();for(int i=0;i<40;i++)f.explode(rocket(1,true,true),T);
        check(f.snapshot(out,T,0,0,0)==2048,"dense fireworks remain bounded");f.clear();check(f.snapshot(out,2*T,0,0,0)==0,"world clear removes particles and pending starters");
        EntityTracker tracker=new EntityTracker(4);tracker.addEntity(spawn(10));
        ByteBuffer meta=ByteBuffer.allocate(65536);VarInts.write(meta,10);meta.put((byte)8);VarInts.write(meta,7);meta.put((byte)1);VarInts.write(meta,1066);meta.put((byte)1).put(tag(2,1,1,false,false)).put((byte)255).flip();tracker.setMetadata(meta);
        long now=System.nanoTime();tracker.status(10,17,now);ByteBuffer remove=ByteBuffer.allocate(8);VarInts.write(remove,1);VarInts.write(remove,10);remove.flip();tracker.removeEntities(remove);
        check(tracker.fireworks.snapshot(out,now,0,0,0)==122,"status 17 explosion survives server entity removal");tracker.clear();check(tracker.fireworks.snapshot(out,now,0,0,0)==0,"tracker reset clears effects");
    }
    private static ProjectileMotion rocket(int type,boolean trail,boolean flicker) throws Exception {
        ProjectileMotion p=new ProjectileMotion();p.reset(37,0,0,0,0,0,0,0,0,T);p.firework=FireworkData.read(tag(type,1,1,trail,flicker));return p;
    }
    private static ByteBuffer spawn(int id) throws Exception {
        ByteBuffer b=ByteBuffer.allocate(96);VarInts.write(b,id);BinaryCodec.writeUuid(b,new UUID(0,id));VarInts.write(b,37);
        b.putDouble(0).putDouble(0).putDouble(0).put((byte)0).put((byte)0).put((byte)0);VarInts.write(b,0);b.putShort((short)0).putShort((short)0).putShort((short)0).flip();return b;
    }
    private static void check(boolean value,String message) {if(!value)throw new AssertionError(message);}
}
