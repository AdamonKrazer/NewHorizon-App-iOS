package com.newhorizon.thinclient.world;

/** Worker-only block lighting. Fixed scratch space, no world locks or GL calls. */
public final class BlockLightEngine {
    private static final int SIZE=31,PLANE=SIZE*SIZE,CELLS=PLANE*SIZE,RADIUS=15;
    public static final int BYTES=CELLS*10;
    private final int[] states=new int[CELLS],changed=new int[CELLS];
    private final byte[] before=new byte[CELLS],levels=new byte[CELLS];
    private int minX,minY,minZ,count;

    public void relight(WorldChunkStore.MeshSnapshot snapshot,int worldX,int worldY,int worldZ) {
        minX=worldX-RADIUS;minY=worldY-RADIUS;minZ=worldZ-RADIUS;count=0;
        SurfaceChunk center=snapshot.neighbors[4];
        if(center==null)return;
        // A changed voxel cannot affect block light at Manhattan distance 15:
        // every edge attenuates at least one and the greatest emission is 15.
        // Keep that boundary authoritative, then rebuild the bounded interior.
        for(int y=0;y<SIZE;y++)for(int z=0;z<SIZE;z++)for(int x=0;x<SIZE;x++) {
            int i=(y*SIZE+z)*SIZE+x,wx=minX+x,wz=minZ+z,ry=minY+y-snapshot.minY;
            int dx=(wx>>4)-center.chunkX,dz=(wz>>4)-center.chunkZ;
            SurfaceChunk chunk=dx>=-1&&dx<=1&&dz>=-1&&dz<=1?snapshot.neighbors[(dz+1)*3+dx+1]:null;
            int state=chunk==null||ry<0||ry>=chunk.fullBlockHeight()?-1:chunk.fullBlockState(wx&15,ry,wz&15);
            states[i]=state;
            int old=state<0?0:chunk.light(false,wx&15,ry,wz&15);
            before[i]=(byte)old;
            int source=state<0?0:BlockStateLighting.emission(state);
            if(boundary(x,y,z))source=Math.max(source,old);
            levels[i]=(byte)source;
        }
        // Descending buckets without queue entries: each voxel is propagated
        // exactly once at its greatest level, with a fixed 15 scans of 31^3 cells.
        for(int level=15;level>1;level--)for(int i=0;i<CELLS;i++) {
            if(levels[i]!=level||states[i]<0)continue;
            int x=i%SIZE,z=(i/SIZE)%SIZE,y=i/PLANE;
            if(y>1)spread(i,i-PLANE,0,level);
            if(y<SIZE-2)spread(i,i+PLANE,1,level);
            if(z>1)spread(i,i-SIZE,2,level);
            if(z<SIZE-2)spread(i,i+SIZE,3,level);
            if(x>1)spread(i,i-1,4,level);
            if(x<SIZE-2)spread(i,i+1,5,level);
        }
        for(int y=1;y<SIZE-1;y++)for(int z=1;z<SIZE-1;z++)for(int x=1;x<SIZE-1;x++) {
            int i=(y*SIZE+z)*SIZE+x;
            if(states[i]>=0&&levels[i]!=before[i])changed[count++]=i;
        }
    }
    private void spread(int from,int to,int face,int level) {
        if(boundary(to%SIZE,to/PLANE,(to/SIZE)%SIZE))return;
        int target=states[to];if(target<0)return;
        int next=level-Math.max(1,BlockStateLighting.opacity(target));
        if(next<=levels[to]||BlockStateLighting.occludes(states[from],target,face))return;
        levels[to]=(byte)next;
    }
    private static boolean boundary(int x,int y,int z) {
        return x==0||x==SIZE-1||y==0||y==SIZE-1||z==0||z==SIZE-1;
    }
    public int changedCount(){return count;}
    public int changedX(int index){return minX+changed[index]%SIZE;}
    public int changedY(int index){return minY+changed[index]/PLANE;}
    public int changedZ(int index){return minZ+(changed[index]/SIZE)%SIZE;}
    public int changedLevel(int index){return levels[changed[index]];}
}
