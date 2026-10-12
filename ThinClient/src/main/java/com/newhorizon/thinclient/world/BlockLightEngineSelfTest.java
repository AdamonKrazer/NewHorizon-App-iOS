package com.newhorizon.thinclient.world;

import com.newhorizon.thinclient.memory.MemoryBudget;
import java.nio.ByteBuffer;

/** Actual state changes without LightUpdate packets: the vanilla interior-chunk path. */
public final class BlockLightEngineSelfTest {
    public static void run() {
        try(WorldChunkStore owner=new WorldChunkStore(MemoryBudget.lowRamDefaults(),9,16)) {
            WorldChunkStore.MeshSnapshot snapshot=owner.createMeshSnapshot();snapshot.minY=-64;
            for(int dz=-1;dz<=1;dz++)for(int dx=-1;dx<=1;dx++) {
                SurfaceChunk c=new SurfaceChunk(ByteBuffer.allocate(0),ByteBuffer.allocate(64*256*2),ByteBuffer.allocate(0),ByteBuffer.allocate(SurfaceChunk.LIGHT_BYTES),ByteBuffer.allocate(0));
                c.reset(dx,dz);c.finishFullBlockUpdate(64);snapshot.neighbors[(dz+1)*3+dx+1]=c;
            }
            BlockLightEngine engine=new BlockLightEngine();
            int torch=VanillaBlockTextures.defaultState("torch"),stone=VanillaBlockTextures.defaultState("stone"),glass=VanillaBlockTextures.defaultState("glass"),water=VanillaBlockTextures.defaultState("water");
            put(snapshot,15,-33,8,torch);apply(engine,snapshot,15,-33,8);
            for(int dz=-13;dz<=13;dz++)for(int dx=-13;dx<=13;dx++) {
                int expected=Math.max(0,14-Math.abs(dx)-Math.abs(dz));
                check(light(snapshot,15+dx,-33,8+dz)==expected,"torch Manhattan falloff across chunk boundary");
            }
            check(light(snapshot,16,-33,8)==13,"adjacent chunk is lit");
            put(snapshot,15,-33,8,0);apply(engine,snapshot,15,-33,8);
            check(light(snapshot,15,-33,8)==0&&light(snapshot,16,-33,8)==0,"removed torch clears old light");
            put(snapshot,15,-33,8,torch);put(snapshot,11,-33,8,torch);
            apply(engine,snapshot,15,-33,8);apply(engine,snapshot,11,-33,8);
            put(snapshot,15,-33,8,0);apply(engine,snapshot,15,-33,8);
            check(light(snapshot,15,-33,8)==10&&light(snapshot,11,-33,8)==14,"removal preserves second source");
            put(snapshot,11,-33,8,0);apply(engine,snapshot,11,-33,8);
            // Enclose a one-cell-wide corridor: light cannot leak around a blocker.
            for(int x=6;x<=23;x++)for(int y=-35;y<=-31;y++)for(int z=6;z<=10;z++)
                put(snapshot,x,y,z,y==-33&&z==8?0:stone);
            put(snapshot,9,-33,8,torch);apply(engine,snapshot,9,-33,8);
            check(light(snapshot,13,-33,8)==10,"open corridor lit");
            put(snapshot,11,-33,8,stone);apply(engine,snapshot,11,-33,8);
            check(light(snapshot,12,-33,8)==0,"placing opaque block removes propagated light");
            put(snapshot,11,-33,8,glass);apply(engine,snapshot,11,-33,8);
            check(light(snapshot,13,-33,8)==10,"glass transmits block light");
            put(snapshot,11,-33,8,water);apply(engine,snapshot,11,-33,8);
            check(light(snapshot,13,-33,8)==10,"water attenuates by vanilla opacity");
            put(snapshot,11,-33,8,0);apply(engine,snapshot,11,-33,8);
            check(light(snapshot,13,-33,8)==10,"breaking obstruction restores light");
            long start=System.nanoTime();for(int n=0;n<16;n++)engine.relight(snapshot,9,-33,8);
            System.out.println("Block light engine passed: torch place/remove, overlapping sources, cross-chunk, opaque/glass/water, negative Y; worker sample ms="+((System.nanoTime()-start)/16/1_000_000.0));
        }
    }
    private static void apply(BlockLightEngine engine,WorldChunkStore.MeshSnapshot s,int x,int y,int z) {
        engine.relight(s,x,y,z);
        for(int i=0;i<engine.changedCount();i++) {
            int wx=engine.changedX(i),wy=engine.changedY(i),wz=engine.changedZ(i);
            chunk(s,wx,wz).setLight(false,wx&15,wy-s.minY,wz&15,engine.changedLevel(i));
        }
    }
    private static SurfaceChunk chunk(WorldChunkStore.MeshSnapshot s,int x,int z) {
        return s.neighbors[((z>>4)+1)*3+(x>>4)+1];
    }
    private static void put(WorldChunkStore.MeshSnapshot s,int x,int y,int z,int state) {
        chunk(s,x,z).putFullBlockState(x&15,y-s.minY,z&15,state);
    }
    private static int light(WorldChunkStore.MeshSnapshot s,int x,int y,int z) {
        return chunk(s,x,z).light(false,x&15,y-s.minY,z&15);
    }
    private static void check(boolean v,String message){if(!v)throw new AssertionError(message);}
}
