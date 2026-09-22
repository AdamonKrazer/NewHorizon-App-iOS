package com.newhorizon.thinclient.world;

import com.newhorizon.thinclient.memory.MemoryBudget;
import com.newhorizon.thinclient.protocol.VarInts;
import java.nio.ByteBuffer;

/** Mutation-driven invalidation of all eight meshing neighbours, including fluid corners. */
public final class WorldChunkNeighborsSelfTest {
    private WorldChunkNeighborsSelfTest() { }
    public static void run()throws Exception {
        WorldChunkStore world=world(12,-1);
        SurfaceChunk[] neighbours=new SurfaceChunk[9];
        world.applyVanillaChunkData(0,0,emptyChunk());
        SurfaceChunk center=chunk(world,0,0);int initial=center.revision;
        world.applyVanillaChunkData(1,1,emptyChunk());
        check(center.revision>initial,"diagonal chunk arrival dirties resident corner");
        for(int z=-1;z<=1;z++)for(int x=-1;x<=1;x++) {
            if((x==0&&z==0)||(x==1&&z==1))continue;
            world.applyVanillaChunkData(x,z,emptyChunk());
        }
        world.applyVanillaChunkData(2,2,emptyChunk());SurfaceChunk remote=chunk(world,2,2);
        world.copyMeshingNeighbors(0,0,neighbours);
        for(int z=-1;z<=1;z++)for(int x=-1;x<=1;x++) {
            SurfaceChunk value=neighbours[(z+1)*3+x+1];check(value!=null&&value.chunkX==x&&value.chunkZ==z,"3x3 order "+x+","+z);
        }
        int[] before=revisions(neighbours);int remoteBefore=remote.revision;
        check(world.applyVanillaBlockUpdate(15,0,15,80),"corner fluid block update applied");
        for(int i=0;i<9;i++)check(neighbours[i].revision==before[i]+((i==4||i==5||i==7||i==8)?1:0),"only south-east corner dependants invalidated "+i);
        check(remote.revision==remoteBefore,"invalidation never cascades to two-away diagonal");
        before=revisions(neighbours);world.applyVanillaBlockUpdate(8,0,8,80);
        for(int i=0;i<9;i++)check(neighbours[i].revision==before[i]+(i==4?1:0),"interior change rebuilds only its own chunk "+i);
        before=revisions(neighbours);world.applyVanillaBlockUpdate(15,0,8,80);
        for(int i=0;i<9;i++)check(neighbours[i].revision==before[i]+((i==4||i==5)?1:0),"east edge changes only east neighbour "+i);
        before=revisions(neighbours);world.applyVanillaBlockUpdate(0,0,0,80);
        for(int i=0;i<9;i++)check(neighbours[i].revision==before[i]+((i==0||i==1||i==3||i==4)?1:0),"north-west corner invalidates negative-coordinate neighbours "+i);
        before=revisions(neighbours);world.applyVanillaBlockUpdate(15,0,15,80);
        for(int i=0;i<9;i++)check(neighbours[i].revision==before[i],"identical state does not rebuild "+i);
        assertNeighboursChange(world,neighbours,0); // auxiliary
        assertNeighboursChange(world,neighbours,1); // biome
        assertNeighboursChange(world,neighbours,2); // light
        assertNeighboursChange(world,neighbours,3); // block entity
        before=revisions(neighbours);world.applyVanillaChunkData(0,0,emptyChunk());
        for(int i=0;i<9;i++)check(neighbours[i].revision>before[i],"existing chunk replacement invalidates "+i);
        int diagonalRevision=center.revision;world.forgetVanillaChunk(1,1);
        check(center.revision>diagonalRevision,"diagonal unload invalidates center");
        world.copyMeshingNeighbors(0,0,neighbours);check(neighbours[8]==null,"unloaded reference cleared");
        world.copyMeshingNeighbors(50,-50,neighbours);for(SurfaceChunk value:neighbours)check(value==null,"old array entries overwritten with null");
        boolean rejected=false;try{world.copyMeshingNeighbors(0,0,new SurfaceChunk[8]);}catch(IllegalArgumentException expected){rejected=true;}check(rejected,"invalid neighbour output rejected");
        int centerBeforeReset=center.revision;world.handle(new WorldMessage.Reset("minecraft:the_nether",0,16));
        check(world.size()==0&&center.revision>centerBeforeReset,"world reset invalidates resident references");
        world.copyMeshingNeighbors(0,0,neighbours);for(SurfaceChunk value:neighbours)check(value==null,"reset clears neighbourhood");
        world.close();
        evictionAndBridge();coldPromotion();
        System.out.println("World chunk neighbour tests passed: 3x3 references, load/unload/reuse/reset, corner updates, auxiliary data, cold promotion and non-cascading revisions.");
    }
    private static void assertNeighboursChange(WorldChunkStore world,SurfaceChunk[] neighbours,int operation)throws Exception {
        int[] before=revisions(neighbours);
        if(operation==0)world.applyVanillaChunkAuxData(0,0,ByteBuffer.wrap(new byte[7]));
        if(operation==1)world.applyVanillaBiomeData(0,0,ByteBuffer.wrap(new byte[]{0,1,0}));
        if(operation==2)world.applyVanillaLightData(0,0,ByteBuffer.wrap(new byte[6]));
        if(operation==3)world.applyVanillaBlockEntity(0,0,0,1);
        for(int i=0;i<9;i++)check(neighbours[i].revision>before[i],"auxiliary mutation "+operation+" neighbour "+i);
    }
    private static void evictionAndBridge()throws Exception {
        WorldChunkStore world=world(3,-1);world.handle(new WorldMessage.PlayerPosition(0,1,0,0,0));
        world.applyVanillaChunkData(0,0,emptyChunk());world.applyVanillaChunkData(1,0,emptyChunk());world.applyVanillaChunkData(2,0,emptyChunk());
        SurfaceChunk near=chunk(world,1,0),evicted=chunk(world,2,0);int nearRevision=near.revision,oldRevision=evicted.revision;
        world.applyVanillaChunkData(-1,0,emptyChunk());
        check(near.revision>nearRevision,"eviction dirties old location neighbours");
        check(chunk(world,2,0)==null&&chunk(world,-1,0)==evicted&&evicted.revision>oldRevision,"reused slot retains local revision sequence");
        nearRevision=near.revision;world.applyVanillaChunkData(90,90,emptyChunk());check(near.revision==nearRevision,"rejected far chunk does not dirty residents");
        world.close();
        world=world(3,-1);world.handle(surface(0,0,400));world.handle(surface(1,1,1));
        SurfaceChunk origin=chunk(world,0,0),diagonal=chunk(world,1,1);int originRevision=origin.revision,diagonalRevision=diagonal.revision;
        world.handle(surface(0,0,1));check(origin.revision>originRevision&&diagonal.revision>diagonalRevision,"bridge remote revision cannot undo local dirty revision");
        originRevision=origin.revision;world.handle(new WorldMessage.RemoveChunk(1,1));check(origin.revision>originRevision,"bridge unload invalidates diagonal");
        world.close();
    }
    private static void coldPromotion()throws Exception {
        WorldChunkStore world=world(9,1);
        world.applyVanillaChunkData(0,0,emptyChunk());world.applyVanillaChunkData(1,1,emptyChunk());
        world.applyVanillaChunkAuxData(1,1,ByteBuffer.wrap(new byte[7]));
        world.applyVanillaBiomeData(1,1,ByteBuffer.wrap(new byte[]{0,1,0}));
        world.applyVanillaBlockUpdate(16,0,16,80);
        check(world.size()==0,"pre-position chunks remain cold");
        world.handle(new WorldMessage.PlayerPosition(-1,1,-1,0,0));
        SurfaceChunk center=chunk(world,0,0);check(center!=null&&chunk(world,1,1)==null,"first active radius promotes only resident neighbor");
        int before=center.revision;
        world.handle(new WorldMessage.PlayerPosition(1,1,1,0,0));
        check(chunk(world,1,1)!=null&&center.revision>before,"cold diagonal promotion dirties existing center");
        check(world.blockStateAt(16,0,16)==80&&world.biomeAt(16,0,16)==1,"cold replay and auxiliary projection retained");
        world.close();
    }
    private static WorldChunkStore world(int capacity,int radius) {
        WorldChunkStore world=new WorldChunkStore(MemoryBudget.lowRamDefaults(),capacity,16,radius);
        world.handle(new WorldMessage.Reset("minecraft:overworld",0,16));return world;
    }
    private static ByteBuffer emptyChunk() {
        ByteBuffer out=ByteBuffer.allocate(16);out.putShort((short)0).put((byte)0);VarInts.write(out,0);VarInts.write(out,0);
        out.put((byte)0);VarInts.write(out,0);VarInts.write(out,0);out.flip();return out;
    }
    private static WorldMessage.ChunkSurface surface(int x,int z,int revision){return new WorldMessage.ChunkSurface(x,z,revision,0,ByteBuffer.allocate(0));}
    private static SurfaceChunk chunk(WorldChunkStore world,int x,int z) {
        SurfaceChunk[] neighbours=new SurfaceChunk[9];world.copyMeshingNeighbors(x,z,neighbours);return neighbours[4];
    }
    private static int[] revisions(SurfaceChunk[] chunks){int[] result=new int[chunks.length];for(int i=0;i<chunks.length;i++)result[i]=chunks[i].revision;return result;}
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
