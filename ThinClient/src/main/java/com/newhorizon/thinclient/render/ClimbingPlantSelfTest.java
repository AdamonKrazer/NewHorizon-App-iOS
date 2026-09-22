package com.newhorizon.thinclient.render;

import java.nio.ByteBuffer;
import java.lang.reflect.*;
import com.newhorizon.thinclient.world.SurfaceChunk;

public final class ClimbingPlantSelfTest {
    public static void run()throws Exception {
        ByteBuffer mesh=ByteBuffer.allocate(4096);
        // Every foliage palette must survive the compact full-tile UV representation.
        for(int biome=0;biome<128;biome++)for(int u:new int[]{0,16})for(int v:new int[]{0,16}){
            int t=ClimbingPlantModels.token(73,u,v,ClimbingPlantModels.FOLIAGE|ClimbingPlantModels.UNSHADED,biome);
            check((t&4095)==73,"tile retained");check(((t>>>12)&16)==u&&((t>>>23)&16)==v,"UV endpoints retained");
            check(((((t>>>12)&15)<<3)|((t>>>23)&7))==biome,"all biome IDs retained");check((t&(262144|524288))==0,"foliage neither liquid nor emissive");
        }
        int[] states={6860,6852,6864,6867,6866},axes={2,0,2,0,1};float[] positions={.05f,.95f,.95f,.05f,.95f};
        for(int i=0;i<states.length;i++){
            mesh.clear();check(ClimbingPlantModels.append(mesh,states[i],0,0,0,127)==12,"single attachment has two faces");
            for(int p=0;p<mesh.position();p+=16)near(mesh.getFloat(p+axes[i]*4),positions[i],"vine flush with correct supporting side");
        }
        for(int state=6837;state<=6868;state++){mesh.clear();int n=ClimbingPlantModels.append(mesh,state,0,0,0,127);check(n==Integer.bitCount(ClimbingPlantModels.vineMask(state))*12,"all multipart combinations");}
        for(int state=12804;state<=12815;state++){
            mesh.clear();int n=ClimbingPlantModels.append(mesh,state,0,0,0,127),leaf=(state-12804)%6/2;
            check(n==(leaf==0?36:60),"cylindrical stalk plus independently selected leaves");
            float min=2,max=-1;for(int p=0;p<36*16;p+=16){min=Math.min(min,mesh.getFloat(p));max=Math.max(max,mesh.getFloat(p));}
            near(max-min,(state<12810?2:3)/16f,"original 2/3 pixel stalk thickness");
            for(int p=12*16;p<36*16;p+=6*16){int a=token(mesh,p),b=token(mesh,p+16);check(Math.abs(((a>>>12)&31)-((b>>>12)&31))==(state<12810?2:3),"stalk side uses narrow atlas strip");}
        }
        for(int x=-24;x<=24;x++)for(int z=-24;z<=24;z++){
            long seed=ClimbingPlantModels.seed(x,0,z);float ox=ClimbingPlantModels.offset(seed,0),oz=ClimbingPlantModels.offset(seed,8);
            check(Math.abs(ox)<=.25&&Math.abs(oz)<=.25,"vanilla XZ jitter bounds");
            int expected=Math.abs((int)new java.util.Random(new java.util.Random(ClimbingPlantModels.seed(x,9,z)).nextLong()).nextLong())%4;
            check(ClimbingPlantModels.variant(x,9,z)==expected,"weighted multipart variant matches vanilla random source");
            mesh.clear();ClimbingPlantModels.append(mesh,12804,x,0,z,0);float firstX=mesh.getFloat(0),firstZ=mesh.getFloat(8);
            near(firstX,x+ox+7/16f,"XZ offset uses low seed nibble after Mth shift");near(firstZ,z+oz+9/16f,"XZ offset uses seed bits 8..11 after Mth shift");
            mesh.clear();ClimbingPlantModels.append(mesh,12804,x,9,z,0);near(mesh.getFloat(0),firstX,"stacked stems share X offset");near(mesh.getFloat(8),firstZ,"stacked stems share Z offset");
        }
        ByteBuffer small=ByteBuffer.allocate(60*16);small.position(1);check(ClimbingPlantModels.append(small,12814,0,0,0,0)==0&&small.position()==1,"bounded mesh refuses incomplete plant");
        integration(6860,12);integration(12814,60);integration(12803,24);
        System.out.println("Climbing plant tests passed: 32 vine states, 12 bamboo states, sapling, 128 biome palettes, original stalk UVs, 2401 column offsets/variants and full mesher integration");
    }
    private static void integration(int state,int count)throws Exception{
        Constructor<SurfaceChunk> constructor=SurfaceChunk.class.getDeclaredConstructor(ByteBuffer.class,ByteBuffer.class,ByteBuffer.class,ByteBuffer.class,ByteBuffer.class);constructor.setAccessible(true);
        SurfaceChunk chunk=constructor.newInstance(ByteBuffer.allocate(0),ByteBuffer.allocate(16*16*16*2),ByteBuffer.allocate(SurfaceChunk.BIOME_BYTES),ByteBuffer.allocate(0),ByteBuffer.allocate(0));
        Method reset=SurfaceChunk.class.getDeclaredMethod("reset",int.class,int.class);reset.setAccessible(true);reset.invoke(chunk,0,0);
        Method finish=SurfaceChunk.class.getDeclaredMethod("finishFullBlockUpdate",int.class);finish.setAccessible(true);finish.invoke(chunk,16);
        Method put=SurfaceChunk.class.getDeclaredMethod("putFullBlockState",int.class,int.class,int.class,int.class);put.setAccessible(true);put.invoke(chunk,8,1,8,state);
        ByteBuffer mesh=ByteBuffer.allocate(4096);check(SurfaceMesher.mesh(chunk,0,mesh)==count,"no extra full cube or grass planes");
        ByteBuffer light=ByteBuffer.allocate(count*2);MeshLighting.build(chunk,0,null,mesh,count,light);check(light.position()==count*2,"unchanged lighting vertex format");
    }
    private static int token(ByteBuffer b,int p){return (b.get(p+12)&255)|((b.get(p+13)&255)<<8)|((b.get(p+14)&255)<<16)|((b.get(p+15)&255)<<24);}
    private static void near(float a,float b,String message){check(Math.abs(a-b)<.00001f,message);}
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
