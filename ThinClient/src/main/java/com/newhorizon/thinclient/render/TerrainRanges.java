package com.newhorizon.thinclient.render;

import java.nio.ByteBuffer;

/** Conservative ranges of the existing mesh. Neither triangles nor lighting move. */
final class TerrainRanges {
    static final int RANGE_VERTICES=1536;
    static final int CAPACITY=(ChunkMeshWorker.BYTES/SurfaceMesher.VERTEX_BYTES+RANGE_VERTICES-1)/RANGE_VERTICES+1;
    static final int BYTES=CAPACITY*32+16;
    private final float[] bounds=new float[CAPACITY*6];
    private final int[] first=new int[CAPACITY],count=new int[CAPACITY];
    private int size,solidEnd;

    void build(ByteBuffer geometry,int vertices,int opaqueVertices){
        if(vertices%3!=0||opaqueVertices%3!=0||opaqueVertices<0||opaqueVertices>vertices)
            throw new IllegalArgumentException("Triangle ranges");
        size=0;
        append(geometry,0,opaqueVertices);solidEnd=size;
        append(geometry,opaqueVertices,vertices);
    }
    private void append(ByteBuffer geometry,int from,int end){
        for(int start=from;start<end;start+=RANGE_VERTICES){
            int vertices=Math.min(RANGE_VERTICES,end-start);
            first[size]=start;count[size]=vertices;
            ViewFrustum.bounds(geometry,start,vertices,bounds,size*6);size++;
        }
    }
    void copyFrom(TerrainRanges source){
        size=source.size;solidEnd=source.solidEnd;
        System.arraycopy(source.first,0,first,0,size);
        System.arraycopy(source.count,0,count,0,size);
        System.arraycopy(source.bounds,0,bounds,0,size*6);
    }
    /** At most three draws. Small holes are deliberately included to limit driver calls. */
    int visible(ViewFrustum frustum,boolean translucent,int[] draws){
        int from=translucent?solidEnd:0,to=translucent?size:solidEnd;
        int start=-1,end=-1,gap1=-1,gap2=-1,len1=0,len2=0;
        for(int i=from;i<to;i++){
            if(!frustum.visible(bounds,i*6))continue;
            if(start<0)start=first[i];
            else if(first[i]-end>=RANGE_VERTICES*2){
                int gap=first[i]-end;
                if(gap>len1){gap2=gap1;len2=len1;gap1=end;len1=gap;}
                else if(gap>len2){gap2=end;len2=gap;}
            }
            end=first[i]+count[i];
        }
        if(start<0)return 0;
        if(gap2>=0&&gap2<gap1){int temp=gap1;gap1=gap2;gap2=temp;temp=len1;len1=len2;len2=temp;}
        int n=0;
        if(gap1>=0){draws[n++]=start;draws[n++]=gap1-start;start=gap1+len1;}
        if(gap2>=0){draws[n++]=start;draws[n++]=gap2-start;start=gap2+len2;}
        draws[n++]=start;draws[n++]=end-start;
        return n/2;
    }
}
