package com.newhorizon.thinclient.render;

import com.newhorizon.thinclient.world.WorldChunkStore;
import com.newhorizon.thinclient.world.SurfaceChunk;
import com.newhorizon.thinclient.memory.*;
import java.nio.*;
import java.util.Arrays;
import java.util.concurrent.ArrayBlockingQueue;

/** One CPU worker, one reusable 3x3 snapshot and exactly two output buffers. No GL calls. */
public final class ChunkMeshWorker implements AutoCloseable {
    public static final int BYTES=2*1024*1024;
    public static final class Result {
        public final ByteBuffer geometry=ByteBuffer.allocateDirect(BYTES).order(ByteOrder.nativeOrder());
        public final ByteBuffer light=ByteBuffer.allocateDirect(BYTES/8);
        public int slot,x,z,revision,vertices;public long epoch,buildNanos;
    }
    private final WorldChunkStore world;
    private final WorldChunkStore.MeshSnapshot snapshot;
    private final ArrayBlockingQueue<Result> free=new ArrayBlockingQueue<>(2),ready=new ArrayBlockingQueue<>(2);
    private final long[] built;
    private final Thread thread;
    private volatile boolean closed;
    private volatile Throwable failure;

    public ChunkMeshWorker(WorldChunkStore world,MemoryBudget budget,int capacity) {
        this.world=world;built=new long[capacity*3+1];Arrays.fill(built,Long.MIN_VALUE);
        budget.reserve(MemoryCategory.MESH,2L*(BYTES+BYTES/8));
        snapshot=world.createMeshSnapshot();free.add(new Result());free.add(new Result());
        world.deferPromotions();
        thread=new Thread(this::run,"NH-chunk-mesher");thread.setDaemon(true);thread.setPriority(Thread.MIN_PRIORITY);thread.start();
    }
    private void run() {
        try {
            while(!closed) {
                // Promotion must keep up with movement even while both mesh
                // outputs wait for incremental GPU uploads on the render thread.
                world.serviceColdPromotion();
                Result result=free.poll();
                if(result==null){Thread.sleep(2);continue;}
                if(!world.nextMeshSnapshot(snapshot,built)){free.add(result);Thread.sleep(8);continue;}
                long start=System.nanoTime();SurfaceChunk center=snapshot.neighbors[4];
                result.vertices=SurfaceMesher.mesh(center,snapshot.minY,result.geometry,snapshot.neighbors);
                MeshLighting.build(center,snapshot.minY,snapshot.neighbors,result.geometry,result.vertices,result.light);
                result.geometry.flip();result.light.flip();result.buildNanos=System.nanoTime()-start;
                result.slot=snapshot.slot;result.x=center.chunkX;result.z=center.chunkZ;
                result.revision=center.revision;result.epoch=snapshot.epoch;
                if(built[built.length-1]!=result.epoch)Arrays.fill(built,Long.MIN_VALUE);
                built[built.length-1]=result.epoch;int i=result.slot*3;
                built[i]=result.x;built[i+1]=result.z;built[i+2]=result.revision;
                ready.put(result);
            }
        } catch(InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        catch(Throwable error){failure=error;}
    }
    public Result poll() {
        if(failure!=null)throw new IllegalStateException("Chunk meshing failed",failure);
        return ready.poll();
    }
    public void release(Result result){free.add(result);}
    public void close(){closed=true;thread.interrupt();}
}
