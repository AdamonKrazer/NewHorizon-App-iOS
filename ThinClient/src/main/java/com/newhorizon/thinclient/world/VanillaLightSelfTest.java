package com.newhorizon.thinclient.world;

import com.newhorizon.thinclient.memory.MemoryBudget;
import com.newhorizon.thinclient.protocol.VarInts;
import java.nio.ByteBuffer;

/** Checks protocol-763 lighting independently from meshes, textures and shaders. */
public final class VanillaLightSelfTest {
    public static void main(String[] args) throws Exception {
        run();
        if(args.length>0) {
            SurfaceChunk chunk=new SurfaceChunk(ByteBuffer.allocate(0),ByteBuffer.allocate(0),ByteBuffer.allocate(0),ByteBuffer.allocate(SurfaceChunk.LIGHT_BYTES),ByteBuffer.allocate(0));
            chunk.fullBlockHeight=384;
            new VanillaChunkAuxDecoder().decodeLightUpdate(ByteBuffer.wrap(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(args[0]))),chunk,384);
            for(int y=0;y<16;y++)for(int z=0;z<16;z++)for(int x=0;x<16;x++) {
                int expected=Math.max(0,14-Math.abs(x-5)-Math.abs(y-3)-Math.abs(z-11));
                check(chunk.light(false,x,128+y,z)==expected,"official DataLayer block nibble");
                check(chunk.light(true,x,128+y,z)==15,"official DataLayer sky nibble");
            }
            System.out.println("Official wh/DataLayer fixture passed: 8192 light nibbles");
        }
    }
    public static void run() throws Exception {
        try(WorldChunkStore world=new WorldChunkStore(MemoryBudget.lowRamDefaults(),1,16)) {
            world.handle(new WorldMessage.Reset("minecraft:overworld",-64,32));
            world.applyVanillaChunkWithLight(0,0,air(),initial(2,2,0,0,-1,-2));
            for(int y=0;y<16;y++) for(int z=0;z<16;z++) for(int x=0;x<16;x++) {
                int cell=(y<<8)|(z<<4)|x;
                check(world.lightAt(true,x,y-64,z)==(cell&15),"sky nibble "+cell);
                check(world.lightAt(false,x,y-64,z)==(15-(cell&15)),"block nibble "+cell);
            }
            world.applyVanillaLightData(0,0,update(0,4,0,0,0,9));
            check(world.lightAt(false,0,-48,0)==9,"negative section origin + top border");
            check(world.lightAt(false,1,-64,0)==14&&world.lightAt(true,1,-64,0)==1,"partial block update preserves other sections and sky");
            world.applyVanillaLightData(0,0,update(0,0,4,2,0,0));
            check(world.lightAt(false,1,-64,0)==0&&world.lightAt(false,0,-48,0)==9,"empty block mask clears only its section");
            check(world.lightAt(true,0,-48,0)==0,"empty sky mask is zero, not absent/default fifteen");
            WorldChunkStore.MeshSnapshot snapshot=world.createMeshSnapshot();
            long[] versions={Long.MIN_VALUE,0,0,Long.MIN_VALUE};
            check(world.nextMeshSnapshot(snapshot,versions),"light revision publishes snapshot");
            check(snapshot.neighbors[4].light(false,0,16,0)==9,"snapshot retains distinct block light");
        }
        mergeSections();
        cold(false);
        cold(true);
        System.out.println("Vanilla light tests passed: sky/block nibbles, negative Y, partial/empty masks, snapshots and cold updates/removal");
    }
    private static void mergeSections()throws Exception {
        VanillaLightUpdateMerger merger=new VanillaLightUpdateMerger();
        VanillaChunkAuxDecoder decoder=new VanillaChunkAuxDecoder();
        SurfaceChunk expected=lightOnly(),actual=lightOnly();
        ByteBuffer cumulative=ByteBuffer.allocate(ColdChunkCache.MAX_RECORD_BYTES),next=ByteBuffer.allocate(ColdChunkCache.MAX_RECORD_BYTES);
        java.util.Random random=new java.util.Random(120);
        boolean previous=false;
        for(int packet=0;packet<96;packet++) {
            int s=random.nextInt(34),b=random.nextInt(34),sv=random.nextInt(16),bv=random.nextInt(16);
            long sky=packet%3==0?0:1L<<s,block=packet%5==0?0:1L<<b;
            long emptySky=packet%3==0?1L<<s:0,emptyBlock=packet%5==0?1L<<b:0;
            ByteBuffer data=update(sky,block,emptySky,emptyBlock,sv,bv);
            // Non-zero packet offsets occur after MinecraftConnection reads chunk coordinates.
            ByteBuffer framed=ByteBuffer.allocate(data.remaining()+7);framed.position(7);framed.put(data).flip();framed.position(7);
            decoder.decodeLightUpdate(framed.duplicate(),expected,512);
            merger.merge(previous?cumulative:null,framed,next);
            check(framed.position()==7,"cache merge preserves network input position");
            actual.clearLightData();decoder.decodeLightUpdate(next.duplicate(),actual,512);
            for(int y=0;y<512;y++)for(int x=0;x<16;x++) {
                check(actual.light(true,x,y,7)==expected.light(true,x,y,7),"cumulative sky partial/clear mask section "+(y>>4));
                check(actual.light(false,x,y,7)==expected.light(false,x,y,7),"cumulative block partial/clear mask section "+(y>>4));
            }
            ByteBuffer swap=cumulative;cumulative=next;next=swap;previous=true;
        }
    }
    private static SurfaceChunk lightOnly() {
        SurfaceChunk chunk=new SurfaceChunk(ByteBuffer.allocate(0),ByteBuffer.allocate(0),ByteBuffer.allocate(0),ByteBuffer.allocate(SurfaceChunk.LIGHT_BYTES),ByteBuffer.allocate(0));
        chunk.fullBlockHeight=512;return chunk;
    }
    private static void cold(boolean deferred)throws Exception {
        try(WorldChunkStore world=new WorldChunkStore(MemoryBudget.lowRamDefaults(),1,16,0)) {
            world.handle(new WorldMessage.Reset("minecraft:overworld",-64,32));
            world.handle(new WorldMessage.PlayerPosition(8,-55,8,0,0));
            if(deferred)world.deferPromotions();
            world.applyVanillaChunkWithLight(0,0,air(),initial(2,0,0,6,15,0));
            world.applyVanillaLightData(0,0,update(0,2,0,0,0,14));
            world.applyVanillaLightData(0,0,update(0,4,0,0,0,9));
            check(world.lightAt(false,0,-64,0)==14,"resident torch update");
            world.handle(new WorldMessage.PlayerPosition(168,-55,8,0,0));
            world.applyVanillaChunkWithLight(10,0,air(),initial(2,0,0,6,15,0));
            world.handle(new WorldMessage.PlayerPosition(8,-55,8,0,0));
            if(deferred)world.serviceColdPromotion();
            check(world.lightAt(false,0,-64,0)==14&&world.lightAt(false,0,-48,0)==9,"cold promotion preserves all partial light updates deferred="+deferred);
            world.handle(new WorldMessage.PlayerPosition(168,-55,8,0,0));
            if(deferred)world.serviceColdPromotion();
            world.applyVanillaLightData(0,0,update(0,0,0,2,0,0));
            world.handle(new WorldMessage.PlayerPosition(8,-55,8,0,0));
            if(deferred)world.serviceColdPromotion();
            check(world.lightAt(false,0,-64,0)==0&&world.lightAt(false,0,-48,0)==9,"cold removal overrides previous array, preserving other section");
            world.applyVanillaChunkWithLight(0,0,air(),initial(2,0,0,6,15,0));
            world.handle(new WorldMessage.PlayerPosition(168,-55,8,0,0));
            if(deferred)world.serviceColdPromotion();
            world.handle(new WorldMessage.PlayerPosition(8,-55,8,0,0));
            if(deferred)world.serviceColdPromotion();
            check(world.lightAt(false,0,-48,0)==0,"new full chunk discards stale cached light deltas");
        }
    }
    private static ByteBuffer air() {
        ByteBuffer b=ByteBuffer.allocate(32);
        for(int s=0;s<2;s++) {b.putShort((short)0).put((byte)0);VarInts.write(b,0);VarInts.write(b,0);b.put((byte)0);VarInts.write(b,0);VarInts.write(b,0);}
        b.flip();return b;
    }
    private static ByteBuffer initial(long sky,long block,long emptySky,long emptyBlock,int skyValue,int blockValue) {
        ByteBuffer u=update(sky,block,emptySky,emptyBlock,skyValue,blockValue),b=ByteBuffer.allocate(u.remaining()+1);
        b.put((byte)0).put(u).flip();return b;
    }
    private static ByteBuffer update(long sky,long block,long emptySky,long emptyBlock,int skyValue,int blockValue) {
        ByteBuffer b=ByteBuffer.allocate(64+2048*(Long.bitCount(sky)+Long.bitCount(block)));
        for(long mask:new long[]{sky,block,emptySky,emptyBlock}) {VarInts.write(b,mask==0?0:1);if(mask!=0)b.putLong(mask);}
        arrays(b,sky,skyValue);arrays(b,block,blockValue);b.flip();return b;
    }
    private static void arrays(ByteBuffer b,long mask,int value) {
        VarInts.write(b,Long.bitCount(mask));
        for(int section=0;section<64;section++)if((mask&(1L<<section))!=0) {
            VarInts.write(b,2048);
            for(int i=0;i<2048;i++) {
                int lo=value>=0?value:(value==-1?(i*2)&15:15-((i*2)&15));
                int hi=value>=0?value:(value==-1?(i*2+1)&15:15-((i*2+1)&15));
                b.put((byte)(lo|(hi<<4)));
            }
        }
    }
    private static void check(boolean value,String message) {if(!value)throw new AssertionError(message);}
}