package com.newhorizon.thinclient.world;

import com.newhorizon.thinclient.render.*;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

public final class TerrainTextureSelfTest {
    public static void run() {
        ByteBuffer packed=ByteBuffer.allocate(16);
        int descriptor=921|(3<<12)|(5<<14)|(1<<17)|(1<<18)|(1<<19)|(126<<23);
        SurfaceMesher.putTexture(packed,descriptor,4);
        int word=(packed.get(0)&255)|((packed.get(1)&255)<<8)|((packed.get(2)&255)<<16)|((packed.get(3)&255)<<24);
        check((word&4095)==921&&((word>>12)&3)==3&&((word>>14)&7)==5&&((word>>20)&7)==4&&((word>>23)&127)==126,"packed texture, tint, face and biome survive RGBA byte attributes");
        check((word&SurfaceMesher.TEXTURED)!=0,"texture material marker");
        SurfaceChunk chunk=new SurfaceChunk(ByteBuffer.allocate(8),ByteBuffer.allocate(SurfaceChunk.FULL_BLOCK_BYTES),ByteBuffer.allocate(0),ByteBuffer.allocate(0),ByteBuffer.allocate(0));
        chunk.reset(-5,-28);chunk.finishFullBlockUpdate(16);
        int stone=VanillaBlockTextures.defaultState("minecraft:stone"),dirt=VanillaBlockTextures.defaultState("dirt");
        check(stone>=0&&dirt>=0,"default block states for held items");
        chunk.putFullBlockState(2,0,2,stone);chunk.putFullBlockState(3,0,2,dirt);
        ByteBuffer mesh=ByteBuffer.allocate(32768);int count=SurfaceMesher.mesh(chunk,63,mesh);
        check(count==60,"greedy mesh cannot merge different block textures: vertices="+count+" stone="+stone+" dirt="+dirt);
        for(int i=0;i<count;i++) {
            int offset=i*16+12;int meta=(mesh.get(offset)&255)|((mesh.get(offset+1)&255)<<8)|((mesh.get(offset+2)&255)<<16)|((mesh.get(offset+3)&255)<<24);
            check((meta&4095)<VanillaBlockTextures.tileCount()&&((meta>>23)&127)==127,"bounded tile and missing-biome fallback");
            check(mesh.getFloat(i*16)<0&&mesh.getFloat(i*16+8)<0,"negative world positions retained for repeating UVs");
        }
        chunk.putFullBlockState(3,0,2,stone);count=SurfaceMesher.mesh(chunk,63,mesh);
        check(count==36,"same textures still merge across two blocks without extra geometry");
        // A waterlogged lower slab must retain its half-height solid geometry.
        chunk.putFullBlockState(2,0,2,11023);chunk.putFullBlockState(3,0,2,0);
        count=SurfaceMesher.mesh(chunk,63,mesh);int solid=0;
        for(int i=0;i<count;i++) {
            int p=i*16+12;int meta=(mesh.get(p)&255)|((mesh.get(p+1)&255)<<8)|((mesh.get(p+2)&255)<<16)|((mesh.get(p+3)&255)<<24);
            if((meta&(1<<18))==0) {solid++;check(mesh.getFloat(i*16+4)<=63.50001f,"waterlogged slab does not become a full cube");}
        }
        check(solid==36,"waterlogged slab keeps its original solid shell");
        float[] matrix={1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1};
        FloatBuffer hand=FloatBuffer.allocate(36*9);
        check(HeldBlockMesh.append(hand,stone,matrix)==36&&hand.position()==36*9,"held textured cube stays 36 vertices");
        for(int i=0;i<36;i++)check(hand.get(i*9+7)>=0&&hand.get(i*9+7)<=1&&hand.get(i*9+8)>=0&&hand.get(i*9+8)<=1,"held block UVs stay inside atlas");
        check(HeldBlockMesh.append(FloatBuffer.allocate(36*9-1),stone,matrix)==0,"held item buffer cap is atomic");
        System.out.println("Terrain texture tests passed: packed metadata, negative coordinates, distinct materials, greedy retention, held UVs, bounded buffers");
    }
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
}
