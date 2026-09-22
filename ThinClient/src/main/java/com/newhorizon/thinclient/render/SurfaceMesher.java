package com.newhorizon.thinclient.render;

import com.newhorizon.thinclient.world.SurfaceChunk;
import com.newhorizon.thinclient.world.BlockStatePhysics;
import com.newhorizon.thinclient.world.BlockStateRender;
import com.newhorizon.thinclient.world.RailState;
import com.newhorizon.thinclient.world.FenceState;
import com.newhorizon.thinclient.world.VanillaBlockTextures;

import java.nio.ByteBuffer;

/** Expands compact exposed cells directly into a reusable GPU staging buffer. */
public final class SurfaceMesher {
    public static final int VERTEX_BYTES = 16;
    private static final int FACE_VERTEX_BYTES = 6 * VERTEX_BYTES;
    private static final int PLANT_VERTICES = 24;
    private static final int PLANT_VERTEX_BYTES = PLANT_VERTICES * VERTEX_BYTES;
    private static final float[] FACE_LIGHT = {0.50f, 1.00f, 0.80f, 0.80f, 0.60f, 0.60f};
    public static final int TEXTURED = 0x40000000;
    private static final int WATER_STILL=VanillaBlockTextures.tileId("water_still"),WATER_FLOW=VanillaBlockTextures.tileId("water_flow"),
            LAVA_STILL=VanillaBlockTextures.tileId("lava_still"),LAVA_FLOW=VanillaBlockTextures.tileId("lava_flow");
    // down, up, north, south, west, east; two triangles per face.
    private static final float[][][] FACES = {
            {{0,0,0},{1,0,1},{1,0,0},{0,0,0},{0,0,1},{1,0,1}},
            {{0,1,0},{1,1,0},{1,1,1},{0,1,0},{1,1,1},{0,1,1}},
            {{0,0,0},{1,1,0},{0,1,0},{0,0,0},{1,0,0},{1,1,0}},
            {{0,0,1},{0,1,1},{1,1,1},{0,0,1},{1,1,1},{1,0,1}},
            {{0,0,0},{0,1,0},{0,1,1},{0,0,0},{0,1,1},{0,0,1}},
            {{1,0,0},{1,0,1},{1,1,1},{1,0,0},{1,1,1},{1,1,0}}
    };
    // Two diagonal crossed planes, each emitted in both directions because
    // the main terrain pass keeps back-face culling enabled.
    private static final float[][] PLANT_POINTS = {
            {0.15f,0,0.15f},{0.85f,0,0.85f},{0.85f,1,0.85f},
            {0.15f,0,0.15f},{0.85f,1,0.85f},{0.15f,1,0.15f},
            {0.85f,1,0.85f},{0.85f,0,0.85f},{0.15f,0,0.15f},
            {0.15f,1,0.15f},{0.85f,1,0.85f},{0.15f,0,0.15f},
            {0.85f,0,0.15f},{0.15f,0,0.85f},{0.15f,1,0.85f},
            {0.85f,0,0.15f},{0.15f,1,0.85f},{0.85f,1,0.15f},
            {0.15f,1,0.85f},{0.15f,0,0.85f},{0.85f,0,0.15f},
            {0.85f,1,0.15f},{0.15f,1,0.85f},{0.85f,0,0.15f}
    };
    private static final int[] GREEDY_PLANE = new int[16 * 512];
    private static final byte[] GREEDY_LIGHT = new byte[16 * 512];
    private static boolean lastMeshTruncated;

    private SurfaceMesher() {
    }

    /** Returns the emitted vertex count. Excess faces are deliberately dropped. */
    public static int mesh(SurfaceChunk chunk, int minY, ByteBuffer output) {
        return mesh(chunk,minY,output,null);
    }

    /** Neighborhood references are used only while WorldChunkStore holds its visitor lock. */
    public static int mesh(SurfaceChunk chunk,int minY,ByteBuffer output,SurfaceChunk[] neighbors) {
        if(neighbors!=null&&neighbors.length<9)throw new IllegalArgumentException("3x3 chunk neighborhood");
        lastMeshTruncated = false;
        if (chunk.hasFullBlockStates()) return meshFull(chunk, minY, output,neighbors);
        return meshRecords(chunk, minY, output);
    }

    /** Valid until the next call to {@link #mesh}. The renderer is single-threaded. */
    public static boolean lastMeshWasTruncated() {
        return lastMeshTruncated;
    }

    private static int meshRecords(SurfaceChunk chunk, int minY, ByteBuffer output) {
        output.clear();
        ByteBuffer records = chunk.records();
        int vertices = 0;
        while (records.hasRemaining()) {
            int packed = records.getInt();
            int rgba = records.getInt();
            float baseX = chunk.chunkX * 16f + SurfaceChunk.localX(packed);
            float baseY = minY + SurfaceChunk.relativeY(packed);
            float baseZ = chunk.chunkZ * 16f + SurfaceChunk.localZ(packed);
            int faces = SurfaceChunk.faceMask(packed);
            int variationHash = (int) (baseX * 73428767f)
                    ^ (int) (baseY * 912931f) ^ (int) (baseZ * 4382893f);
            float blockLight = 0.91f + (variationHash & 7) * 0.018f;
            for (int face = 0; face < FACES.length; face++) {
                if ((faces & (1 << face)) == 0) continue;
                if (output.remaining() < FACE_VERTEX_BYTES) {
                    lastMeshTruncated = true;
                    return vertices;
                }
                float[][] points = FACES[face];
                float light = FACE_LIGHT[face] * blockLight;
                for (int vertex = 0; vertex < points.length; vertex++) {
                    output.putFloat(baseX + points[vertex][0]);
                    output.putFloat(baseY + points[vertex][1]);
                    output.putFloat(baseZ + points[vertex][2]);
                    output.put(shaded(rgba >>> 24, light));
                    output.put(shaded(rgba >>> 16, light));
                    output.put(shaded(rgba >>> 8, light));
                    output.put((byte) rgba);
                    vertices++;
                }
            }
        }
        return vertices;
    }

    /** Greedy-meshes the authoritative full chunk without the bridge cell cap. */
    private static int meshFull(SurfaceChunk chunk, int minY, ByteBuffer output,SurfaceChunk[] neighbors) {
        output.clear();
        int vertices = 0;
        int chunkHeight = chunk.fullBlockHeight();
        for (int face = 0; face < FACES.length; face++) {
            int planes = face < 2 ? chunkHeight : 16;
            int planeHeight = face < 2 ? 16 : chunkHeight;
            for (int plane = 0; plane < planes; plane++) {
                fillPlane(chunk, face, plane, 16, planeHeight,neighbors);
                vertices += emitGreedyPlane(chunk, minY, face, plane,
                        16, planeHeight, output);
                if (lastMeshTruncated) return vertices;
            }
        }
        vertices += emitPlants(chunk, minY, output);
        if(lastMeshTruncated)return vertices;
        vertices += FluidSurfaceMesher.append(chunk,minY,neighbors,output);
        lastMeshTruncated=FluidSurfaceMesher.wasTruncated();
        return vertices;
    }

    private static void fillPlane(SurfaceChunk chunk, int face, int plane,
                                  int width, int height,SurfaceChunk[] neighbors) {
        java.util.Arrays.fill(GREEDY_PLANE, 0, width * height, 0);
        for (int v = 0; v < height; v++) {
            for (int u = 0; u < width; u++) {
                int x;
                int y;
                int z;
                if (face < 2) {
                    x = u;
                    y = plane;
                    z = v;
                } else if (face < 4) {
                    x = u;
                    y = v;
                    z = plane;
                } else {
                    x = plane;
                    y = v;
                    z = u;
                }
                int state = chunk.fullBlockState(x, y, z);
                if (BlockStatePhysics.isAir(state)) {
                    continue;
                }
                int fluidMask = BlockStatePhysics.WATER | BlockStatePhysics.LAVA;
                // Fluids use shared corner heights and cross-chunk face visibility.
                // Solid geometry inside waterlogged cells is emitted separately below.
                if((BlockStatePhysics.flags(state)&fluidMask)!=0||BlockStateRender.isPlant(state)
                        ||RailState.isRail(state)||FenceState.isFence(state)||CocoaModel.contains(state)||ClimbingPlantModels.contains(state))continue;
                int nx = x;
                int ny = y;
                int nz = z;
                switch (face) {
                    case 0: ny--; break;
                    case 1: ny++; break;
                    case 2: nz--; break;
                    case 3: nz++; break;
                    case 4: nx--; break;
                    default: nx++; break;
                }
                int neighbour = neighborState(chunk,neighbors,nx,ny,nz);
                if (faceIsOccluded(state, neighbour)) {
                    continue;
                }
                GREEDY_PLANE[v * width + u] = meshToken(
                        chunk, state, x, y, z, face);
                GREEDY_LIGHT[v*width+u]=(byte)MeshLighting.face(chunk,neighbors,x,y,z,face);
            }
        }
    }

    private static int meshToken(SurfaceChunk chunk, int state, int x, int y,
                                 int z, int face) {
        return TEXTURED | VanillaBlockTextures.face(state,face) | biomeBits(chunk,x,y,z);
    }

    private static int neighborState(SurfaceChunk center,SurfaceChunk[] neighbors,int x,int y,int z) {
        if(y<0||y>=center.fullBlockHeight())return -1;
        if((x&~15)==0&&(z&~15)==0)return center.fullBlockState(x,y,z);
        int dx=x>>4,dz=z>>4;
        if(neighbors==null||dx<-1||dx>1||dz<-1||dz>1)return -1;
        SurfaceChunk neighbor=neighbors[(dz+1)*3+dx+1];
        return neighbor==null||y>=neighbor.fullBlockHeight()?-1:neighbor.fullBlockState(x&15,y,z&15);
    }

    /**
     * Conservative vanilla-style face visibility. Fluids and plants cannot
     * erase adjacent terrain; equal fluids only hide their internal faces.
     */
    private static boolean faceIsOccluded(int state, int neighbour) {
        if (neighbour<0||BlockStatePhysics.isAir(neighbour)) return false;
        int fluidMask = BlockStatePhysics.WATER | BlockStatePhysics.LAVA;
        int stateFluid = BlockStatePhysics.flags(state) & fluidMask;
        int neighbourFluid = BlockStatePhysics.flags(neighbour) & fluidMask;
        if (stateFluid != 0) {
            return (stateFluid & neighbourFluid) != 0
                    || BlockStatePhysics.fullyOccludesUnitCube(neighbour);
        }
        int neighbourTexture=VanillaBlockTextures.face(neighbour,1);
        if((neighbourTexture & ((1<<17)|(1<<18)))!=0)return state==neighbour;
        return BlockStatePhysics.fullyOccludesUnitCube(neighbour);
    }

    private static int emitGreedyPlane(SurfaceChunk chunk, int minY, int face,
                                       int plane, int width, int height,
                                       ByteBuffer output) {
        int vertices = 0;
        for (int v = 0; v < height; v++) {
            for (int u = 0; u < width; u++) {
                int token = GREEDY_PLANE[v * width + u];
                if (token == 0) continue;
                byte light=GREEDY_LIGHT[v*width+u];
                int rectangleWidth = 1;
                while (u + rectangleWidth < width
                        && GREEDY_PLANE[v * width + u + rectangleWidth] == token
                        && GREEDY_LIGHT[v * width + u + rectangleWidth] == light) {
                    rectangleWidth++;
                }
                int rectangleHeight = 1;
                heightLoop:
                while (v + rectangleHeight < height) {
                    for (int x = 0; x < rectangleWidth; x++) {
                        if (GREEDY_PLANE[(v + rectangleHeight) * width + u + x]
                                != token || GREEDY_LIGHT[(v+rectangleHeight)*width+u+x]!=light) break heightLoop;
                    }
                    rectangleHeight++;
                }
                for (int row = 0; row < rectangleHeight; row++) {
                    java.util.Arrays.fill(GREEDY_PLANE,
                            (v + row) * width + u,
                            (v + row) * width + u + rectangleWidth, 0);
                }
                if (output.remaining() < FACE_VERTEX_BYTES) {
                    lastMeshTruncated = true;
                    return vertices;
                }
                vertices += emitRectangle(chunk, minY, face, plane, u, v,
                        rectangleWidth, rectangleHeight, token, output);
            }
        }
        return vertices;
    }

    private static int emitRectangle(SurfaceChunk chunk, int minY, int face,
                                     int plane, int u, int v, int width,
                                     int height, int token, ByteBuffer output) {
        float baseX;
        float baseY;
        float baseZ;
        float sizeX;
        float sizeY;
        float sizeZ;
        if (face < 2) {
            baseX = chunk.chunkX * 16f + u;
            baseY = minY + plane;
            baseZ = chunk.chunkZ * 16f + v;
            sizeX = width;
            sizeY = 1f;
            sizeZ = height;
        } else if (face < 4) {
            baseX = chunk.chunkX * 16f + u;
            baseY = minY + v;
            baseZ = chunk.chunkZ * 16f + plane;
            sizeX = width;
            sizeY = height;
            sizeZ = 1f;
        } else {
            baseX = chunk.chunkX * 16f + plane;
            baseY = minY + v;
            baseZ = chunk.chunkZ * 16f + u;
            sizeX = 1f;
            sizeY = height;
            sizeZ = width;
        }
        for (float[] point : FACES[face]) {
            output.putFloat(baseX + point[0] * sizeX);
            output.putFloat(baseY + point[1] * sizeY);
            output.putFloat(baseZ + point[2] * sizeZ);
            putTexture(output,token,face);
        }
        return 6;
    }

    private static final int[] RAIL_CORNERS={0,2,1,0,3,2,0,1,2,0,2,3};

    private static int emitPlants(SurfaceChunk chunk, int minY, ByteBuffer output) {
        int vertices = 0;
        int height = chunk.fullBlockHeight();
        for (int y = 0; y < height; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    int state = chunk.fullBlockState(x,y,z);
                    if(ClimbingPlantModels.contains(state)){
                        int added=ClimbingPlantModels.append(output,state,chunk.chunkX*16+x,minY+y,chunk.chunkZ*16+z,biomeBits(chunk,x,y,z)>>>23);
                        if(added==0){lastMeshTruncated=true;return vertices;}vertices+=added;continue;
                    }
                    if(CocoaModel.contains(state)){
                        if(output.remaining()<CocoaModel.VERTICES*VERTEX_BYTES){lastMeshTruncated=true;return vertices;}
                        vertices+=CocoaModel.append(output,state,chunk.chunkX*16f+x,minY+y,chunk.chunkZ*16f+z);continue;
                    }
                    int fence=FenceState.data(state);
                    if(fence>=0) {
                        int needed=(1+2*Integer.bitCount(fence&15))*36;
                        if(output.remaining()<needed*VERTEX_BYTES){lastMeshTruncated=true;return vertices;}
                        float bx=chunk.chunkX*16f+x,by=minY+y,bz=chunk.chunkZ*16f+z;
                        int color=state;
                        int biome=biomeBits(chunk,x,y,z);
                        box(output,bx,by,bz,.375f,0,.375f,.625f,1,.625f,color,biome);
                        for(int arm=0;arm<4;arm++)if((fence&(1<<arm))!=0)for(int level=0;level<2;level++) {
                            float low=level==0?.375f:.75f,high=low+.1875f;
                            box(output,bx,by,bz,arm==3?0:.4375f,low,arm==0?0:.4375f,
                                    arm==1?1:.5625f,high,arm==2?1:.5625f,color,biome);
                        }
                        vertices+=needed;continue;
                    }
                    int rail = RailState.data(state);
                    if (rail >= 0) {
                        if (output.remaining() < 12*VERTEX_BYTES) { lastMeshTruncated=true; return vertices; }
                        for (int corner : RAIL_CORNERS) {
                            float u=(corner==1||corner==2)?1:0, v=corner>=2?1:0;
                            float rx=u,rz=v;
                            for(int turn=0;turn<((rail>>4)&3);turn++) { float old=rx;rx=1-rz;rz=old; }
                            // Model rotation changes UV orientation; slope stays in world coordinates.
                            output.putFloat(chunk.chunkX*16f+x+rx).putFloat(minY+y+RailState.surfaceHeight(rail&15,rx,rz))
                                    .putFloat(chunk.chunkZ*16f+z+rz);
                            output.put((byte)(u*255)).put((byte)(v*255)).put((byte)(rail>>6)).put((byte)3);
                        }
                        vertices+=12; continue;
                    }
                    int style = BlockStateRender.style(state);
                    if (style == BlockStateRender.CUBE) {
                        int tile=VanillaBlockTextures.face(state,1)&4095;
                        if((BlockStatePhysics.flags(state)&BlockStatePhysics.WATER)!=0&&tile!=WATER_STILL&&tile!=WATER_FLOW) {
                            int boxes=BlockStatePhysics.collisionBoxCount(state);
                            if(output.remaining()<boxes*36*VERTEX_BYTES){lastMeshTruncated=true;return vertices;}
                            for(int b=0;b<boxes;b++) {
                                box(output,chunk.chunkX*16f+x,minY+y,chunk.chunkZ*16f+z,
                                        BlockStatePhysics.collisionCoordinate(state,b,0),BlockStatePhysics.collisionCoordinate(state,b,1),BlockStatePhysics.collisionCoordinate(state,b,2),
                                        BlockStatePhysics.collisionCoordinate(state,b,3),BlockStatePhysics.collisionCoordinate(state,b,4),BlockStatePhysics.collisionCoordinate(state,b,5),state,biomeBits(chunk,x,y,z));
                                vertices+=36;
                            }
                        }
                        continue;
                    }
                    if (output.remaining() < PLANT_VERTEX_BYTES) {
                        lastMeshTruncated = true;
                        return vertices;
                    }
                    int rgba = TEXTURED | VanillaBlockTextures.representative(state) | biomeBits(chunk,x,y,z);
                    float baseX = chunk.chunkX * 16f + x;
                    float baseY = minY + y;
                    float baseZ = chunk.chunkZ * 16f + z;
                    for (float[] point : PLANT_POINTS) {
                        output.putFloat(baseX + point[0]);
                        output.putFloat(baseY + point[1]);
                        output.putFloat(baseZ + point[2]);
                        putTexture(output,rgba,6);
                        vertices++;
                    }
                }
            }
        }
        return vertices;
    }

    private static void box(ByteBuffer out,float bx,float by,float bz,float x0,float y0,float z0,float x1,float y1,float z1,int rgba,int biome) {
        for(int face=0;face<6;face++)for(float[] v:FACES[face]) {
            out.putFloat(bx+x0+(x1-x0)*v[0]).putFloat(by+y0+(y1-y0)*v[1]).putFloat(bz+z0+(z1-z0)*v[2]);
            putTexture(out,TEXTURED|VanillaBlockTextures.face(rgba,face)|biome,face);
        }
    }

    private static int biomeBits(SurfaceChunk chunk,int x,int y,int z) {
        int biome=chunk.biomeState(x>>2,y>>2,z>>2);
        return (biome<0||biome>126?127:biome)<<23;
    }

    /** Four bytes replace RGBA with a texture descriptor; world vertices remain 16 bytes. */
    public static void putTexture(ByteBuffer out,int descriptor,int face) {
        int packed=TEXTURED|(descriptor & 0x3f8fffff)|((face & 7)<<20);
        out.put((byte)packed).put((byte)(packed>>>8)).put((byte)(packed>>>16)).put((byte)(packed>>>24));
    }

    public static int texturedBox(ByteBuffer out,float x,float y,float z,float inset,int descriptor) {
        out.clear();if(out.remaining()<36*VERTEX_BYTES)return 0;
        for(int face=0;face<6;face++)for(float[] point:FACES[face]) {
            out.putFloat(x-inset+point[0]*(1+2*inset)).putFloat(y-inset+point[1]*(1+2*inset)).putFloat(z-inset+point[2]*(1+2*inset));
            putTexture(out,descriptor,face);
        }
        return 36;
    }

    private static byte shaded(int shiftedColor, float light) {
        int value = Math.round((shiftedColor & 0xff) * light);
        return (byte) Math.max(0, Math.min(255, value));
    }
}
