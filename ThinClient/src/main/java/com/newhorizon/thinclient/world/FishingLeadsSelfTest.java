package com.newhorizon.thinclient.world;

import com.newhorizon.thinclient.protocol.*;
import com.newhorizon.thinclient.memory.MemoryBudget;
import com.newhorizon.thinclient.render.*;
import java.nio.ByteBuffer;
import java.util.UUID;

public final class FishingLeadsSelfTest {
    private static final long T=50_000_000L;
    public static void run()throws Exception {
        freeHookVisibility();touchHold();links();fishing();ropesAndFences();
        System.out.println("Fishing/leads tests passed: links, packet validation, owner, hook/bite, buoyancy, pull, particles, rope mesh, 384 fence states, reuse/reset");
    }
    private static void freeHookVisibility()throws Exception {
        EntityTracker tracker=new EntityTracker(8);
        tracker.localPlayer(466,-70.2,63,-432.5);
        tracker.addEntity(spawn(490,123,-70.234,64.62,-432.292,466));
        EntityTracker.Renderable[] visible={new EntityTracker.Renderable()};
        int count=tracker.snapshotVisible(visible,System.nanoTime(),-70.2,64.62,-432.5,72,466);
        check(count==1&&visible[0].entityId==490&&Math.abs(visible[0].x+70.234)<.01&&Math.abs(visible[0].z+432.292)<.01,
                "free hook must stay at its received position, not follow an empty entity slot at the origin");
        double[] position=new double[3];
        check(!tracker.position(-1,System.nanoTime(),position)&&tracker.typeOf(-1)==-1,"missing entity sentinel cannot resolve to an unused slot");
        Rope[] line={new Rope()};
        check(tracker.ropes(line,System.nanoTime(),-70.2,64.62,-432.5)==1&&Math.abs(line[0].x+70.234)<.01,"line starts at the visible bobber away from world origin");
        tracker.addEntity(spawn(491,15,-69,63,-430,0));
        ByteBuffer meta=ByteBuffer.allocate(16);VarInts.write(meta,490);meta.put((byte)8);VarInts.write(meta,1);VarInts.write(meta,492);meta.put((byte)255).flip();tracker.setMetadata(meta);
        check(tracker.position(490,System.nanoTime(),position)&&Math.abs(position[0]+69)<.01,"a real hooked entity still moves the bobber");
        tracker.removeEntities(remove(491));
        check(tracker.position(490,System.nanoTime(),position)&&Math.abs(position[0]+69)<.01,"removed hooked entity does not teleport the bobber to an empty slot");
    }
    private static void touchHold() {
        com.newhorizon.thinclient.inventory.UsePressState press=new com.newhorizon.thinclient.inventory.UsePressState();
        int sends=0;
        // Press and auto-repeat at 200/400/600 ms must send one use, even before spawn arrives.
        for(int tick=0;tick<4;tick++)if(press.allows("minecraft:fishing_rod")){sends++;press.used("minecraft:fishing_rod");}
        check(sends==1,"holding touch use cannot immediately reel the cast back in");
        check(!press.allows("FISHING_ROD"),"another press event without release stays latched, including the offhand");
        check(press.allows("EGG")&&press.allows("OAK_PLANKS"),"other item auto-repeat remains available");
        press.release();check(press.allows("fishing_rod"),"release rearms the next retrieve action");
        press.used("FISHING_ROD");press.release();check(press.allows("FISHING_ROD"),"inventory/focus cancellation rearms use");
    }
    private static void links()throws Exception {
        check(MinecraftPacketIds.PLAY_CLIENTBOUND_ENTITY_LINK==0x53,"protocol 763 entity link ID");
        LeashState links=new LeashState();links.read(link(4,8));check(links.holder(4)==8,"link can arrive before spawn");
        links.read(link(4,9));check(links.holder(4)==9&&!links.hasHolder(8),"transfer replaces holder");
        links.read(link(5,9));links.read(link(4,0));check(links.holder(4)<0&&links.holder(5)==9,"zero holder detaches one mob");
        links.remove(9);check(links.holder(5)<0,"removed knot clears dependent ropes");
        links.read(link(0,0));links.read(link(7,12));
        for(ByteBuffer bad:new ByteBuffer[]{ByteBuffer.allocate(7),ByteBuffer.allocate(9),link(7,-1),link(7,7)}) {
            boolean rejected=false;try{links.read(bad);}catch(ProtocolException expected){rejected=true;}check(rejected&&links.holder(7)==12,"invalid link does not corrupt state");
        }
        links.clear();for(int i=0;i<300;i++)links.read(link(i+1,999));check(links.holder(1)==999&&links.holder(256)==999&&links.holder(257)<0,"fixed capacity preserves admitted ropes");
        check(ProjectileKind.airUseItem("minecraft:fishing_rod")&&!ProjectileKind.chargedItem("FISHING_ROD")&&ProjectileKind.blockUseItem("LEAD"),"rod is a click action, lead targets blocks");
        check(ProjectileKind.leadHand("LEAD","LEAD")==0&&ProjectileKind.leadHand("AIR","LEAD")==1&&ProjectileKind.leadHand("mod:lead","AIR")==-1,"lead hand priority and namespaces");
        ByteBuffer packet=ByteBuffer.allocate(20);MinecraftPackets.writeInteractEntity(packet,17,true,1);packet.flip();
        check(VarInts.read(packet)==MinecraftPacketIds.PLAY_SERVERBOUND_INTERACT&&VarInts.read(packet)==17&&VarInts.read(packet)==0&&VarInts.read(packet)==1&&packet.get()==1&&!packet.hasRemaining(),"offhand lead interaction wire format");
        check(CombatHitboxes.pickable(58)&&!CombatHitboxes.pickable(123),"knot is selectable, bobber does not intercept use");
    }
    private static void fishing()throws Exception {
        ProjectileMotion p=new ProjectileMotion();p.reset(123,0,4,0,0,0,0,0,1,0);p.advance(2*T,null);
        check(Math.abs(p.z-1.92)<1e-8&&Math.abs(p.y-3.9124)<1e-7,"hook gravity precedes movement and drag is .92");
        ByteBuffer metadata=ByteBuffer.wrap(new byte[]{8,1,18,9,8,1,(byte)255});ProjectileMetadata.read(metadata,p);
        check(p.hooked==17&&p.biting&&p.vy<=-.24&&p.vy>=-.4,"hooked entity id plus one and bite impulse");
        double y=p.y;p.advance(3*T,null);check(p.y==y,"hooked bobber stops free flight");
        ProjectileMetadata.read(ByteBuffer.wrap(new byte[]{8,1,0,9,8,0,(byte)255}),p);check(p.hooked==-1&&!p.biting,"server unhooks and ends bite");
        p.reset(24,0,0,0,0,0,0,0,0,0);check(p.owner==-1&&p.hooked==-1&&!p.biting&&!p.bobbing,"slot reset removes fishing state");
        WorldChunkStore world=world();world.applyVanillaBlockUpdate(3,0,3,80);
        check(world.waterHeightAt(3,0,3)>.8,"source water height from authoritative block states");
        p.reset(123,3.5,.5,3.5,0,0,.2,-.2,.1,0);p.advance(T,world);check(p.bobbing&&p.x==3.5&&Math.abs(p.vx-.06)<1e-8,"entering water changes hook state and damps velocity");
        p.reset(123,3.5,.5,3.5,0,0,0,0,0,0);for(int tick=1;tick<=80;tick++)p.advance(tick*T,world);
        check(p.y>.5&&p.y<1.05,"buoyancy keeps bobber near water surface");world.close();
        EntityTracker tracker=new EntityTracker(8);tracker.localPlayer(200,0,0,0);tracker.localRopeView(0,0,false);
        tracker.addEntity(spawn(10,123,0,1,4,200));check(tracker.fishing(200)&&!tracker.fishing(201),"spawn data associates hook with its owner");
        Rope[] ropes={new Rope(),new Rope()};check(tracker.ropes(ropes,System.nanoTime(),0,1,0)==1&&ropes[0].fishing,"line connects hook to local hand");
        double right=ropes[0].endX;tracker.localRopeView(0,0,true);tracker.ropes(ropes,System.nanoTime(),0,1,0);check(right*ropes[0].endX<0,"line changes side for offhand rod");
        tracker.localRopeView(90,0,false);tracker.localFishingTip(.4f,.2f,-.8f,1.54f);
        tracker.ropes(ropes,System.nanoTime(),0,1,0);
        check(Math.abs(ropes[0].endX+.8)<1e-6&&Math.abs(ropes[0].endY-1.74)<1e-6&&Math.abs(ropes[0].endZ+.4)<1e-6,"cast line uses rendered rod tip and crouching eye height");
        tracker.localRopeView(0,90,false);tracker.localFishingTip(.4f,.2f,-.8f,1.62f);
        tracker.ropes(ropes,System.nanoTime(),0,1,0);
        check(Math.abs(ropes[0].endY-.82)<1e-6&&Math.abs(ropes[0].endZ-.2)<1e-6,"tip follows camera pitch");
        tracker.addEntity(spawn(20,122,4,0,0,0));tracker.addEntity(spawn(11,123,2,1,0,20));
        ByteBuffer meta=ByteBuffer.allocate(16);VarInts.write(meta,11);meta.put((byte)8);VarInts.write(meta,1);VarInts.write(meta,201);meta.put((byte)255).flip();tracker.setMetadata(meta);
        CombatState combat=new CombatState();tracker.pullHookedPlayer(11,200,combat);double[] motion=new double[3];
        check(combat.drainMotion(motion)==2&&Math.abs(motion[0]-.4)<1e-8,"event 31 pulls local hooked player toward owner");
        tracker.removeEntities(remove(10));check(!tracker.fishing(200),"reeling/removal restores rod cast state");tracker.clear();check(tracker.ropes(ropes,System.nanoTime(),0,0,0)==0,"world change clears fishing lines");
        FishingParticles particles=new FishingParticles();FishingParticles.Particle[] out=new FishingParticles.Particle[192];for(int i=0;i<out.length;i++)out[i]=new FishingParticles.Particle();
        particles.read(particle(27,0),T);check(particles.snapshot(out,T,null)==1,"count zero is one directional wake");
        double z=out[0].z;particles.snapshot(out,2*T,null);check(out[0].z>z&&out[0].tile>=44&&out[0].tile<=47,"server velocity and wake texture frames");
        particles.clear();particles.read(particle(58,100000),T);check(particles.snapshot(out,T,null)==192,"splash particle count bounded");
        check(particles.snapshot(out,100*T,null)==0,"stale particles expire without catch-up storm");
        ByteBuffer bad=particle(27,1);bad.limit(bad.limit()-1);boolean rejected=false;try{particles.read(bad,T);}catch(ProtocolException expected){rejected=true;}check(rejected,"truncated fishing particles rejected");
    }
    private static void ropesAndFences()throws Exception {
        Rope rope=new Rope();rope.x=rope.y=rope.z=0;rope.endX=4;rope.endY=4;rope.endZ=0;
        check(Rope.height(0,4,.5,false)==1&&Rope.height(4,0,.5,false)==1,"leash curve hangs below straight segment in either direction");
        ByteBuffer mesh=ByteBuffer.allocate(RopeMesh.BYTES);check(RopeMesh.mesh(mesh,new Rope[]{rope},1,0,3,-2)==24*12,"two ribbons over 24 leash segments");
        rope.endX=rope.endZ=0;int n=RopeMesh.mesh(mesh,new Rope[]{rope},1,0,3,-2);
        for(int i=0;i<n;i++)check(Float.isFinite(mesh.getFloat(i*16))&&Float.isFinite(mesh.getFloat(i*16+4)),"vertical hanging rope remains finite");
        rope.fishing=true;check(RopeMesh.mesh(mesh,new Rope[]{rope},1,0,3,-2)==16*6,"16 segment fishing curve");
        check(RopeMesh.mesh(ByteBuffer.allocate(16*6*16-1),new Rope[]{rope},1,0,3,-2)==0,"buffer exhaustion keeps ropes atomic");
        SurfaceChunk chunk=new SurfaceChunk(ByteBuffer.allocate(8),ByteBuffer.allocate(SurfaceChunk.FULL_BLOCK_BYTES),ByteBuffer.allocate(0),ByteBuffer.allocate(0),ByteBuffer.allocate(0));
        chunk.reset(0,0);chunk.finishFullBlockUpdate(16);ByteBuffer terrain=ByteBuffer.allocate(32768);int states=0;
        for(int id=0;id<24135;id++)if(FenceState.isFence(id)) {
            states++;chunk.putFullBlockState(2,0,2,id);n=SurfaceMesher.mesh(chunk,0,terrain);int expected=(1+Integer.bitCount(FenceState.data(id)&15)*2)*36;
            boolean water=(BlockStatePhysics.flags(id)&BlockStatePhysics.WATER)!=0;check(water?n>expected:n==expected,"fence post/arms and water state "+id);
        }
        check(states==384,"all vanilla fences retain connection geometry");
        EntityTracker tracker=new EntityTracker(4);tracker.localPlayer(200,0,0,0);tracker.addEntity(spawn(1,49,1,0,3,0));tracker.addEntity(spawn(2,15,4,0,3,0));tracker.addEntity(spawn(3,58,3.5,.375,5.5,0));
        tracker.leashes.read(link(1,200));tracker.leashes.read(link(2,3));Rope[] lines={new Rope(),new Rope()};
        check(tracker.ropes(lines,System.nanoTime(),0,1,0)==2&&!lines[0].fishing&&lines[1].endY>.375,"horse/player and chicken/fence knot ropes coexist");
        tracker.leashes.read(link(1,3));tracker.removeEntities(remove(3));check(tracker.ropes(lines,System.nanoTime(),0,1,0)==0,"removing knot detaches every rope to it");
        tracker.leashes.read(link(1,200));tracker.clear();check(!tracker.leashes.hasHolder(200),"reset clears leash ownership");
    }
    public static WorldChunkStore world()throws Exception {
        WorldChunkStore w=new WorldChunkStore(MemoryBudget.lowRamDefaults(),2,16);w.handle(new WorldMessage.Reset("minecraft:overworld",0,16));
        ByteBuffer b=ByteBuffer.allocate(20);b.putShort((short)0).put((byte)0);VarInts.write(b,0);VarInts.write(b,0);b.put((byte)0);VarInts.write(b,0);VarInts.write(b,0);b.flip();w.applyVanillaChunkData(0,0,b);return w;
    }
    public static ByteBuffer spawn(int id,int type,double x,double y,double z,int owner) {
        ByteBuffer b=ByteBuffer.allocate(96);VarInts.write(b,id);BinaryCodec.writeUuid(b,new UUID(0,id));VarInts.write(b,type);b.putDouble(x).putDouble(y).putDouble(z).put((byte)0).put((byte)0).put((byte)0);VarInts.write(b,owner);b.putShort((short)0).putShort((short)0).putShort((short)0).flip();return b;
    }
    public static ByteBuffer link(int mob,int holder){ByteBuffer b=ByteBuffer.allocate(8);b.putInt(mob).putInt(holder).flip();return b;}
    private static ByteBuffer remove(int id){ByteBuffer b=ByteBuffer.allocate(10);VarInts.write(b,1);VarInts.write(b,id);b.flip();return b;}
    private static ByteBuffer particle(int type,int count){ByteBuffer b=ByteBuffer.allocate(64);VarInts.write(b,type);b.put((byte)0).putDouble(0).putDouble(1).putDouble(0).putFloat(0).putFloat(0).putFloat(.04f).putFloat(1).putInt(count).flip();return b;}
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
}
