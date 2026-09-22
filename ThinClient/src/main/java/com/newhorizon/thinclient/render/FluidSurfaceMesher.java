package com.newhorizon.thinclient.render;

import com.newhorizon.thinclient.world.BlockStatePhysics;
import com.newhorizon.thinclient.world.SurfaceChunk;
import com.newhorizon.thinclient.world.VanillaBlockTextures;
import java.nio.ByteBuffer;
import java.util.Arrays;

/** Bounded fluid geometry with shared vanilla corner heights across chunk borders. */
public final class FluidSurfaceMesher {
    public static final float EPSILON = .001f;
    private static final int QUAD_BYTES = 6 * SurfaceMesher.VERTEX_BYTES;
    private static final int[] TOP = new int[256], BOTTOM = new int[256], SIDES = new int[4*256];
    private static final float[] HEIGHTS = new float[256*4];
    private static final byte[] LOWER_INSET = new byte[256];
    private static final int[] TRIANGLES = {0,1,2,0,2,3};
    private static final int[] TOP_CORNERS = {0,1,2,3}, BOTTOM_CORNERS = {0,3,2,1};
    private static final int[] SIDE_FIRST = {0,3,0,1}, SIDE_LAST = {1,2,3,2};
    private static final int[] DX = {0,0,0,0,-1,1}, DY = {-1,1,0,0,0,0}, DZ = {0,0,-1,1,0,0};
    private static boolean truncated;
    private FluidSurfaceMesher() {}

    /** Appends to the current position; never clears or flips the caller's buffer. */
    public static int append(SurfaceChunk chunk, int minY, SurfaceChunk[] neighbors, ByteBuffer out) {
        truncated = false;
        if (chunk == null || !chunk.hasFullBlockStates()) return 0;
        int vertices = 0;
        for (int y=0; y<chunk.fullBlockHeight(); y++) {
            Arrays.fill(TOP,0); Arrays.fill(BOTTOM,0); Arrays.fill(SIDES,0); Arrays.fill(LOWER_INSET,(byte)0);
            for (int z=0; z<16; z++) for (int x=0; x<16; x++) {
                int state=chunk.fullBlockState(x,y,z), fluid=fluid(state);
                if (fluid==0) continue;
                int cell=z*16+x, offset=cell*4;
                float center=height(chunk,neighbors,x,y,z,fluid);
                if (center>=1) {
                    HEIGHTS[offset]=HEIGHTS[offset+1]=HEIGHTS[offset+2]=HEIGHTS[offset+3]=1;
                } else {
                    float north=height(chunk,neighbors,x,y,z-1,fluid), south=height(chunk,neighbors,x,y,z+1,fluid);
                    float west=height(chunk,neighbors,x-1,y,z,fluid), east=height(chunk,neighbors,x+1,y,z,fluid);
                    HEIGHTS[offset]=corner(chunk,neighbors,x-1,y,z-1,fluid,center,north,west);
                    HEIGHTS[offset+1]=corner(chunk,neighbors,x+1,y,z-1,fluid,center,north,east);
                    HEIGHTS[offset+2]=corner(chunk,neighbors,x+1,y,z+1,fluid,center,south,east);
                    HEIGHTS[offset+3]=corner(chunk,neighbors,x-1,y,z+1,fluid,center,south,west);
                }
                int biome=chunk.biomeState(x>>2,y>>2,z>>2);
                int biomeBits=(biome<0||biome>126?127:biome)<<23;
                boolean lava=fluid==BlockStatePhysics.LAVA;
                boolean top=visible(chunk,neighbors,x,y,z,state,fluid,1);
                if (top) {
                    for(int i=0;i<4;i++) HEIGHTS[offset+i]=Math.max(EPSILON,HEIGHTS[offset+i]-EPSILON);
                    boolean flat=flat(cell);
                    int descriptor=VanillaBlockTextures.descriptorForFluid(lava,!flat)|biomeBits
                            |(flat?0:flowRotation(cell)<<12);
                    if (flat) TOP[cell]=descriptor;
                    else {
                        vertices+=top(out,chunk,minY,x,y,z,1,1,descriptor,cell,false);
                        if(truncated)return vertices;
                    }
                }
                if(visible(chunk,neighbors,x,y,z,state,fluid,0)) {
                    BOTTOM[cell]=VanillaBlockTextures.descriptorForFluid(lava,false)|biomeBits;
                    LOWER_INSET[cell]=1;
                }
                for(int face=2;face<6;face++) if(visible(chunk,neighbors,x,y,z,state,fluid,face))
                    SIDES[(face-2)*256+cell]=VanillaBlockTextures.descriptorForFluid(lava,true)|biomeBits;
            }
            vertices+=planes(out,chunk,minY,y,TOP,false);
            if(truncated)return vertices;
            vertices+=planes(out,chunk,minY,y,BOTTOM,true);
            if(truncated)return vertices;
            for(int face=2;face<6;face++) for(int plane=0;plane<16;plane++) for(int along=0;along<16;) {
                int x=face<4?along:plane,z=face<4?plane:along,cell=z*16+x;
                int descriptor=SIDES[(face-2)*256+cell];
                if(descriptor==0){along++;continue;}
                int first=SIDE_FIRST[face-2],last=SIDE_LAST[face-2];
                float a=HEIGHTS[cell*4+first],b=HEIGHTS[cell*4+last];
                int width=1;
                if(equal(a,b)) while(along+width<16) {
                    int next=face<4?cell+width:cell+width*16;
                    if(SIDES[(face-2)*256+next]!=descriptor
                            ||LOWER_INSET[next]!=LOWER_INSET[cell]
                            ||!equal(HEIGHTS[next*4+first],a)||!equal(HEIGHTS[next*4+last],a))break;
                    width++;
                }
                vertices+=side(out,chunk,minY,x,y,z,width,face,a,b,LOWER_INSET[cell]!=0?EPSILON:0,descriptor);
                if(truncated)return vertices;
                along+=width;
            }
        }
        return vertices;
    }
    public static boolean wasTruncated(){return truncated;}

    /** Relative positions may cross one chunk in x/z; neighbors use a 3x3 row-major grid. */
    public static int sampleState(SurfaceChunk chunk, SurfaceChunk[] neighbors, int x, int y, int z) {
        if(y<0||y>=chunk.fullBlockHeight())return -1;
        int dx=x>>4,dz=z>>4;
        SurfaceChunk target=chunk;
        if(dx!=0||dz!=0) {
            if(dx < -1||dx > 1||dz < -1||dz > 1||neighbors==null||neighbors.length<9)return -1;
            target=neighbors[(dz+1)*3+dx+1];
            if(target==null||target.chunkX!=chunk.chunkX+dx||target.chunkZ!=chunk.chunkZ+dz
                    ||!target.hasFullBlockStates()||y>=target.fullBlockHeight())return -1;
        }
        return target.fullBlockState(x&15,y,z&15);
    }
    private static int fluid(int state) {
        return state<0?0:BlockStatePhysics.flags(state)&(BlockStatePhysics.WATER|BlockStatePhysics.LAVA);
    }
    private static float height(SurfaceChunk chunk,SurfaceChunk[] neighbors,int x,int y,int z,int fluid) {
        int state=heightState(chunk,neighbors,x,y,z);
        if((fluid(state)&fluid)!=0) {
            if((fluid(heightState(chunk,neighbors,x,y+1,z))&fluid)!=0)return 1;
            return BlockStatePhysics.fluidAmount(state)/9f;
        }
        return state>=0&&BlockStatePhysics.blocksMovement(state)?-1:0;
    }
    private static int heightState(SurfaceChunk chunk,SurfaceChunk[] neighbors,int x,int y,int z) {
        int state=sampleState(chunk,neighbors,x,y,z);
        // Unknown horizontal neighbors are the streaming frontier, not air.
        // Continue the boundary column until real neighbor data remeshes it.
        if(state<0&&y>=0&&y<chunk.fullBlockHeight()&&((x&~15)!=0||(z&~15)!=0))
            return chunk.fullBlockState(Math.max(0,Math.min(15,x)),y,Math.max(0,Math.min(15,z)));
        return state;
    }
    private static float corner(SurfaceChunk chunk,SurfaceChunk[] neighbors,int x,int y,int z,int fluid,
                                float center,float sideA,float sideB) {
        if(sideA>=1||sideB>=1)return 1;
        float diagonal=sideA>0||sideB>0?height(chunk,neighbors,x,y,z,fluid):-1;
        if(diagonal>=1)return 1;
        float weight=weight(center)+weight(sideA)+weight(sideB)+weight(diagonal);
        return weight==0?0:(weighted(center)+weighted(sideA)+weighted(sideB)+weighted(diagonal))/weight;
    }
    private static float weight(float h){return h>=.8f?10:h>=0?1:0;}
    private static float weighted(float h){return h>=0?h*weight(h):0;}
    private static boolean visible(SurfaceChunk chunk,SurfaceChunk[] neighbors,int x,int y,int z,int state,int fluid,int face) {
        if(occludes(state,face))return false;
        int other=sampleState(chunk,neighbors,x+DX[face],y+DY[face],z+DZ[face]);
        // A missing chunk must not create a translucent curtain across the
        // ocean. Known air still exposes genuine banks and waterfalls.
        if(other<0&&face>=2)return false;
        return (fluid(other)&fluid)==0&&!occludes(other,face^1);
    }
    private static boolean occludes(int state,int face) {
        if(state<0||BlockStatePhysics.isAir(state))return false;
        int texture=VanillaBlockTextures.face(state,face);
        if(VanillaBlockTextures.isCutout(texture)||VanillaBlockTextures.isTranslucent(texture))return false;
        if(BlockStatePhysics.fullyOccludesUnitCube(state))return true;
        // A waterlogged top/bottom slab closes one complete horizontal face.
        if(face>=2)return false;
        for(int box=0;box<BlockStatePhysics.collisionBoxCount(state);box++) {
            if(BlockStatePhysics.collisionCoordinate(state,box,0)>0||BlockStatePhysics.collisionCoordinate(state,box,2)>0
                    ||BlockStatePhysics.collisionCoordinate(state,box,3)<1||BlockStatePhysics.collisionCoordinate(state,box,5)<1)continue;
            if(face==0&&BlockStatePhysics.collisionCoordinate(state,box,1)<=0)return true;
            if(face==1&&BlockStatePhysics.collisionCoordinate(state,box,4)>=1)return true;
        }
        return false;
    }
    private static boolean equal(float a,float b){return Math.abs(a-b)<.000001f;}
    private static boolean flat(int cell) {
        int at=cell*4;return equal(HEIGHTS[at],HEIGHTS[at+1])&&equal(HEIGHTS[at],HEIGHTS[at+2])&&equal(HEIGHTS[at],HEIGHTS[at+3]);
    }
    private static int flowRotation(int cell) {
        int at=cell*4;
        double x=HEIGHTS[at]+HEIGHTS[at+3]-HEIGHTS[at+1]-HEIGHTS[at+2];
        double z=HEIGHTS[at]+HEIGHTS[at+1]-HEIGHTS[at+2]-HEIGHTS[at+3];
        return Math.abs(x)+Math.abs(z)<.000001?0:((int)Math.round(Math.atan2(x,z)/(Math.PI*.5)))&3;
    }
    private static int planes(ByteBuffer out,SurfaceChunk chunk,int minY,int y,int[] keys,boolean bottom) {
        int vertices=0;
        for(int z=0;z<16;z++)for(int x=0;x<16;x++) {
            int cell=z*16+x,key=keys[cell];if(key==0)continue;
            float h=bottom?0:HEIGHTS[cell*4];int width=1,height=1;
            while(x+width<16&&keys[cell+width]==key&&(bottom||equal(HEIGHTS[(cell+width)*4],h)))width++;
            rows:while(z+height<16) {
                for(int i=0;i<width;i++)if(keys[cell+height*16+i]!=key
                        ||(!bottom&&!equal(HEIGHTS[(cell+height*16+i)*4],h)))break rows;
                height++;
            }
            for(int row=0;row<height;row++)Arrays.fill(keys,cell+row*16,cell+row*16+width,0);
            vertices+=top(out,chunk,minY,x,y,z,width,height,key,cell,bottom);
            if(truncated)return vertices;
        }
        return vertices;
    }
    private static int top(ByteBuffer out,SurfaceChunk chunk,int minY,int x,int y,int z,int width,int depth,
                           int descriptor,int cell,boolean bottom) {
        if(out.remaining()<QUAD_BYTES){truncated=true;return 0;}
        int[] order=bottom?BOTTOM_CORNERS:TOP_CORNERS;
        boolean merged=width!=1||depth!=1;
        for(int index:TRIANGLES) {
            int corner=order[index];
            float px=chunk.chunkX*16f+x+(corner==1||corner==2?width:0);
            float pz=chunk.chunkZ*16f+z+(corner>=2?depth:0);
            float py=minY+y+(bottom?EPSILON:HEIGHTS[cell*4+(merged?0:corner)]);
            out.putFloat(px).putFloat(py).putFloat(pz);SurfaceMesher.putTexture(out,descriptor,bottom?0:1);
        }
        return 6;
    }
    private static int side(ByteBuffer out,SurfaceChunk chunk,int minY,int x,int y,int z,int width,int face,
                            float a,float b,float lower,int descriptor) {
        if(out.remaining()<QUAD_BYTES){truncated=true;return 0;}
        float bx=chunk.chunkX*16f+x,bz=chunk.chunkZ*16f+z;
        // North/east: bottomA,topB,topA,bottomA,bottomB,topB; south/west reverse.
        boolean reverse=face==3||face==4;
        for(int vertex=0;vertex<6;vertex++) {
            int corner=reverse?newCornerReverse(vertex):newCorner(vertex);
            boolean end=corner==1||corner==2,upper=corner>=2;
            float px=face<4?bx+(end?width:0):bx+(face==4?EPSILON:1-EPSILON);
            float pz=face<4?bz+(face==2?EPSILON:1-EPSILON):bz+(end?width:0);
            float py=minY+y+(upper?(end?b:a):lower);
            out.putFloat(px).putFloat(py).putFloat(pz);SurfaceMesher.putTexture(out,descriptor,face);
        }
        return 6;
    }
    // Corner numbering bottomA=0,bottomB=1,topB=2,topA=3.
    private static int newCorner(int vertex){return vertex==0||vertex==3?0:vertex==1||vertex==5?2:vertex==2?3:1;}
    private static int newCornerReverse(int vertex){return vertex==0||vertex==3?0:vertex==1?3:vertex==4?2:vertex==2?2:1;}
}
