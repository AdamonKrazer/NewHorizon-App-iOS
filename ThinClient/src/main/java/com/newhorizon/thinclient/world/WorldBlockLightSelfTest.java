package com.newhorizon.thinclient.world;

import com.newhorizon.thinclient.memory.MemoryBudget;
import com.newhorizon.thinclient.protocol.VarInts;
import java.nio.ByteBuffer;

/** World/worker handoff regression without any authoritative LightUpdate packet. */
public final class WorldBlockLightSelfTest {
    public static void main(String[] args)throws Exception {run();}
    public static void run()throws Exception {
        BlockLightEngine engine=new BlockLightEngine();
        int torch=VanillaBlockTextures.defaultState("torch"),stone=VanillaBlockTextures.defaultState("stone");
        try(WorldChunkStore world=new WorldChunkStore(MemoryBudget.lowRamDefaults(),9,16)) {
            world.handle(new WorldMessage.Reset("minecraft:overworld",-64,64));
            world.applyVanillaChunkWithLight(0,0,air(),dark());
            WorldChunkStore.MeshSnapshot snapshot=world.createMeshSnapshot();
            world.chunkAt(0).setLight(false,15,31,8,6);
            world.applyVanillaBlockUpdate(15,-33,8,torch);
            check(!world.nextLightSnapshot(snapshot),"missing source columns defer local rebuild");
            check(world.lightAt(false,15,-33,8)==6,"deferral preserves previously received light");
            for(int cz=-1;cz<=1;cz++)for(int cx=-1;cx<=1;cx++)if(cx!=0||cz!=0)
                world.applyVanillaChunkWithLight(cx,cz,air(),dark());
            drain(world,snapshot,engine);
            check(world.lightAt(false,15,-33,8)==14,"block update alone illuminates torch once neighbors arrive");
            check(world.lightAt(false,16,-33,8)==13,"arriving neighbor receives existing source light");
            world.applyVanillaBlockUpdate(15,-33,8,0);
            check(world.nextLightSnapshot(snapshot),"removal queues light work");
            engine.relight(snapshot,snapshot.lightX,snapshot.lightY,snapshot.lightZ);
            world.applyVanillaBlockUpdate(18,-33,8,stone);
            check(!world.commitBlockLight(snapshot,engine),"neighbor revision rejects stale light output");
            drain(world,snapshot,engine);
            check(world.lightAt(false,15,-33,8)==0&&world.lightAt(false,16,-33,8)==0,"stale work retries and removal reaches both chunks");
            world.applyVanillaBlockUpdate(15,-33,8,torch);
            check(world.nextLightSnapshot(snapshot),"new source queued");
            engine.relight(snapshot,snapshot.lightX,snapshot.lightY,snapshot.lightZ);
            world.handle(new WorldMessage.Reset("minecraft:the_nether",-64,64));
            check(!world.commitBlockLight(snapshot,engine)&&!world.nextLightSnapshot(snapshot),"dimension epoch discards old output and queue");
        }
        try(WorldChunkStore world=new WorldChunkStore(MemoryBudget.lowRamDefaults(),9,16,1)) {
            world.handle(new WorldMessage.Reset("minecraft:overworld",-64,64));
            world.handle(new WorldMessage.PlayerPosition(8,-33,8,0,0));world.deferPromotions();
            for(int z=-1;z<=1;z++)for(int x=-1;x<=1;x++)world.applyVanillaChunkWithLight(x,z,air(),dark());
            WorldChunkStore.MeshSnapshot snapshot=world.createMeshSnapshot();
            world.applyVanillaBlockUpdate(8,-33,8,torch);drain(world,snapshot,engine);
            check(world.lightAt(false,8,-33,8)==14,"local source before eviction");
            world.handle(new WorldMessage.PlayerPosition(168,-33,8,0,0));
            for(int z=-1;z<=1;z++)for(int x=9;x<=11;x++)world.applyVanillaChunkWithLight(x,z,air(),dark());
            world.handle(new WorldMessage.PlayerPosition(8,-33,8,0,0));
            for(int i=0;i<9;i++)world.serviceColdPromotion();
            drain(world,snapshot,engine);
            check(world.blockStateAt(8,-33,8)==torch&&world.lightAt(false,8,-33,8)==14,"cold journal replays source then recomputes local light");
        }
        System.out.println("World block light integration passed: no light packet, arriving neighbors, stale revisions, epochs, cold replay");
    }
    private static void drain(WorldChunkStore world,WorldChunkStore.MeshSnapshot snapshot,BlockLightEngine engine) {
        int count=0;
        while(world.nextLightSnapshot(snapshot)) {
            check(++count<128,"bounded fixture queue drains");
            engine.relight(snapshot,snapshot.lightX,snapshot.lightY,snapshot.lightZ);
            check(world.commitBlockLight(snapshot,engine),"current light snapshot commits");
        }
    }
    private static ByteBuffer air() {
        ByteBuffer b=ByteBuffer.allocate(48);
        for(int s=0;s<4;s++){b.putShort((short)0).put((byte)0);VarInts.write(b,0);VarInts.write(b,0);b.put((byte)0);VarInts.write(b,0);VarInts.write(b,0);}
        b.flip();return b;
    }
    private static ByteBuffer dark() {
        ByteBuffer b=ByteBuffer.allocate(32);VarInts.write(b,0);VarInts.write(b,0);VarInts.write(b,0);
        VarInts.write(b,1);b.putLong(63);VarInts.write(b,1);b.putLong(63);
        VarInts.write(b,0);VarInts.write(b,0);b.flip();return b;
    }
    private static void check(boolean test,String message){if(!test)throw new AssertionError(message);}
}