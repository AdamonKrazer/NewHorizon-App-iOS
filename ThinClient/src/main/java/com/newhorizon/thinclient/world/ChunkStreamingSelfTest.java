package com.newhorizon.thinclient.world;

import com.newhorizon.thinclient.memory.MemoryBudget;
import com.newhorizon.thinclient.protocol.VarInts;
import com.newhorizon.thinclient.render.ChunkMeshWorker;
import java.nio.ByteBuffer;
import java.util.Arrays;

public final class ChunkStreamingSelfTest {
    public static void run()throws Exception {
        MemoryBudget budget=MemoryBudget.lowRamDefaults();
        WorldChunkStore world=new WorldChunkStore(budget,9,16,1);
        world.handle(new WorldMessage.Reset("minecraft:overworld",0,16));
        world.handle(new WorldMessage.PlayerPosition(8,4,8,0,0));
        world.applyVanillaChunkWithLight(0,0,air(),lit());
        int sand=VanillaBlockTextures.defaultState("sand");
        world.applyVanillaBlockUpdate(8,1,8,sand);
        SurfaceChunk live=world.chunkAt(0);live.setLight(true,8,1,8,0);
        world.applyVanillaBlockUpdate(8,1,8,0);
        check(world.lightAt(true,8,1,8)==15,"mining exposes skylight before server light packet");
        live.setLight(true,8,2,8,0);live.setLight(false,8,2,8,12);
        for(int z=7;z<=9;z++)for(int x=7;x<=9;x++)live.setLight(true,x,1,z,0);
        live.setLight(true,8,0,8,0);
        world.applyVanillaBlockUpdate(8,1,8,sand);live.setLight(true,8,1,8,0);
        world.applyVanillaBlockUpdate(8,1,8,0);
        check(world.lightAt(true,8,1,8)==0&&world.lightAt(false,8,1,8)==11,"caves stay dark and torch attenuation remains one level");
        world.applyVanillaBlockUpdate(2,1,2,sand);
        WorldChunkStore.MeshSnapshot snapshot=world.createMeshSnapshot();long[] versions=new long[28];Arrays.fill(versions,Long.MIN_VALUE);
        check(world.nextMeshSnapshot(snapshot,versions),"dirty snapshot available");
        int revision=snapshot.neighbors[4].revision;
        world.applyVanillaBlockUpdate(2,1,2,0);
        check(snapshot.neighbors[4].fullBlockState(2,1,2)==sand&&snapshot.neighbors[4].revision==revision,"network updates cannot mutate worker geometry or light");
        world.applyVanillaBlockUpdate(2,1,2,sand);
        try(ChunkMeshWorker worker=new ChunkMeshWorker(world,budget,9)) {
            ChunkMeshWorker.Result first=await(worker);
            check(first.x==0&&first.vertices>0&&first.geometry.remaining()==first.vertices*16&&first.light.remaining()==first.vertices*2,"complete geometry/light pair published");
            int saved=first.geometry.getInt(0);
            for(int i=0;i<64;i++)world.applyVanillaBlockUpdate(3,1,3,i%2==0?sand:0);
            ChunkMeshWorker.Result second=await(worker);
            check(first.geometry.getInt(0)==saved&&first.revision<second.revision,"bounded output buffers are never overwritten before release");
            worker.release(first);worker.release(second);
            long oldEpoch=second.epoch;
            world.handle(new WorldMessage.Reset("minecraft:the_nether",0,16));world.copyMeshVersions(versions);
            check(versions[27]!=oldEpoch,"dimension reset rejects stale results");
            world.applyVanillaChunkWithLight(1,0,air(),lit());
            world.handle(new WorldMessage.PlayerPosition(16,4,0,0,0));
            ChunkMeshWorker.Result after=await(worker);
            check(after.epoch!=oldEpoch,"worker rebuilds after dimension reset");worker.release(after);
        }
        world.close();
        WorldChunkStore cold=new WorldChunkStore(MemoryBudget.lowRamDefaults(),9,16,1);cold.deferPromotions();
        cold.handle(new WorldMessage.Reset("minecraft:overworld",0,16));cold.applyVanillaChunkWithLight(0,0,air(),lit());
        cold.applyVanillaBlockUpdate(2,1,2,sand);cold.handle(new WorldMessage.PlayerPosition(8,4,8,0,0));
        check(cold.size()==0,"moving across chunk border does no synchronous disk/decode work");
        cold.serviceColdPromotion();check(cold.blockStateAt(2,1,2)==sand&&cold.lightAt(true,2,2,2)==15,"background promotion preserves block journal and lighting");cold.close();
        WorldChunkStore traveling=new WorldChunkStore(MemoryBudget.lowRamDefaults(),9,16,1);
        traveling.handle(new WorldMessage.Reset("minecraft:overworld",0,16));
        traveling.handle(new WorldMessage.PlayerPosition(8,4,8,0,0));
        traveling.applyVanillaChunkWithLight(0,0,air(),lit());
        traveling.applyVanillaChunkWithLight(1,0,air(),lit());
        traveling.applyVanillaChunkWithLight(10,0,air(),lit());
        try(ChunkMeshWorker worker=new ChunkMeshWorker(traveling,MemoryBudget.lowRamDefaults(),9)){
            ChunkMeshWorker.Result heldA=await(worker),heldB=await(worker);
            traveling.handle(new WorldMessage.PlayerPosition(168,4,8,0,0));
            long end=System.nanoTime()+2_000_000_000L;
            while(!traveling.hasFullBlockData(168,4,8)&&System.nanoTime()<end)Thread.sleep(2);
            check(traveling.hasFullBlockData(168,4,8),"cold promotion progresses with both upload buffers occupied");
            worker.release(heldA);worker.release(heldB);
        }finally{traveling.close();}
        System.out.println("Chunk streaming tests passed: off-thread snapshots, bounded queue, mining light, cave/torch, epochs and deferred cold promotion");
    }
    private static ChunkMeshWorker.Result await(ChunkMeshWorker worker)throws Exception {
        long end=System.nanoTime()+5_000_000_000L;ChunkMeshWorker.Result result;
        while((result=worker.poll())==null&&System.nanoTime()<end)Thread.sleep(2);
        if(result==null)throw new AssertionError("worker result timeout");return result;
    }
    private static ByteBuffer air(){ByteBuffer b=ByteBuffer.allocate(16);b.putShort((short)0).put((byte)0);VarInts.write(b,0);VarInts.write(b,0);b.put((byte)0);VarInts.write(b,0);VarInts.write(b,0);b.flip();return b;}
    private static ByteBuffer lit(){ByteBuffer b=ByteBuffer.allocate(2080);VarInts.write(b,0);VarInts.write(b,1);b.putLong(2);VarInts.write(b,0);VarInts.write(b,0);VarInts.write(b,1);b.putLong(2);VarInts.write(b,1);VarInts.write(b,2048);for(int i=0;i<2048;i++)b.put((byte)255);VarInts.write(b,0);b.flip();return b;}
    private static void check(boolean condition,String label){if(!condition)throw new AssertionError(label);}
}
