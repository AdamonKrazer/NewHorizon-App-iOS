package com.newhorizon.thinclient.render;

import com.newhorizon.thinclient.world.*;
import java.nio.*;
import java.lang.reflect.*;

/** Catalog coverage plus regression fixtures for original, non-cube world geometry. */
public final class VanillaBlockModelsSelfTest {
    public static void run() throws Exception {
        check(VanillaBlockModels.stateCount()==24135,"protocol 763 states");
        ByteBuffer mesh=ByteBuffer.allocate(VanillaBlockModels.maxVertexCount()*16);
        int modeled=0,greedy=0,unsupported=0;
        for(int state=0;state<24135;state++) {
            int route=VanillaBlockModels.route(state);
            if(route==-2){greedy++;continue;}if(route==-1){unsupported++;continue;}
            modeled++;mesh.clear();int count=VanillaBlockModels.append(mesh,state,0,0,0,127,0);
            check(count==VanillaBlockModels.vertexCount(state)&&mesh.position()==count*16,"complete bounded model "+state);
            for(int p=0;p<mesh.position();p+=16){
                for(int a=0;a<3;a++)check(Float.isFinite(mesh.getFloat(p+a*4)),"finite geometry");
                int token=token(mesh,p),corner=token&VanillaBlockModels.CORNER_MASK;
                check(VanillaBlockModels.isToken(token)&&((token>>>20)&127)==127,"model token and biome");
                int descriptor=VanillaBlockModels.descriptor(corner);
                check((descriptor&4095)<VanillaBlockTextures.tileCount(),"existing original texture");
            }
            if(count>0){mesh.clear();mesh.limit(count*16-1);check(VanillaBlockModels.append(mesh,state,0,0,0,0,0)==-1&&mesh.position()==0,"no partial block on buffer exhaustion");mesh.clear();}
        }
        for(int biome=0;biome<128;biome++) {
            int token=VanillaBlockModels.token(VanillaBlockModels.cornerCount()-1,biome);
            check(VanillaBlockModels.isToken(token)&&((token>>>20)&127)==biome,"all biome palettes retain model flag");
        }
        ByteBuffer packed=ByteBuffer.allocate(BlockModelCodec.BYTES);
        for(int corner=0;corner<VanillaBlockModels.cornerCount();corner++){
            packed.clear();BlockModelCodec.put(packed,VanillaBlockModels.descriptor(corner),VanillaBlockModels.textureU(corner),VanillaBlockModels.textureV(corner),VanillaBlockModels.shade(corner));
            int[] n=new int[12];for(int i=0;i<12;i++){int uploaded=packed.get(i)&255;int rgba4=Math.round(uploaded/255f*15);n[i]=Math.round((rgba4/15f)*15);}
            int d=n[0]|n[1]<<4|n[2]<<8|n[3]<<12|n[4]<<16;
            check(d==VanillaBlockModels.descriptor(corner),"RGBA4 preserves every material bit");
            check((n[5]|n[6]<<4|n[7]<<8)/1024f==VanillaBlockModels.textureU(corner),"RGBA4 keeps source U exactly");
            check((n[8]|n[9]<<4|n[10]<<8)/1024f==VanillaBlockModels.textureV(corner),"RGBA4 keeps source V exactly");
            check(n[11]/10f==VanillaBlockModels.shade(corner),"RGBA4 keeps directional shade exactly");
        }
        int torch=state("torch");mesh.clear();VanillaBlockModels.append(mesh,torch,0,0,0,0,0);
        check(mesh.position()==36*16,"torch keeps two caps and four transparent side planes from template_torch.json");
        for(int p=0;p<mesh.position();p+=6*16){
            int corner=token(mesh,p)&VanillaBlockModels.CORNER_MASK,face=VanillaBlockModels.lightFace(corner);
            int axis=face<2?1:face<4?2:0;
            float plane=face==0?0:face==1?10/16f:face%2==0?7/16f:9/16f;
            for(int v=0;v<6;v++)check(Math.abs(mesh.getFloat(p+v*16+axis*4)-plane)<1e-5,"torch surface stays on original thin rod planes");
            check(!VanillaBlockModels.shaded(corner),"original torch disables directional shade");
        }
        int slab=state("oak_slab");mesh.clear();VanillaBlockModels.append(mesh,slab,0,0,0,0,0);
        check(Math.abs(span(mesh,1)-.5f)<1e-5,"bottom slab half height");
        for(String name:new String[]{"torch","wall_torch","redstone_torch","soul_torch","oak_slab","oak_stairs","oak_door","oak_trapdoor","ladder","lantern","campfire","chest","red_bed"}){
            int id=state(name);check(VanillaBlockModels.vertexCount(id)>0,name+" original geometry exists");integration(id);
        }
        Method occludes=SurfaceMesher.class.getDeclaredMethod("faceIsOccluded",int.class,int.class);occludes.setAccessible(true);
        check(!(Boolean)occludes.invoke(null,11023,80),"surrounding water cannot erase a waterlogged slab's solid faces");
        check(VanillaBlockModels.route(state("stone"))==-2,"ordinary stone keeps greedy mesh");
        System.out.println("Original block models: modeled="+modeled+" greedy="+greedy+" unsupported="+unsupported+"; catalog, torch, half slabs, multipart, buffer bounds and light integration passed");
    }
    private static int state(String name){int state=VanillaBlockTextures.defaultState(name);check(state>=0,"state "+name);return state;}
    private static float span(ByteBuffer b,int axis){float lo=Float.POSITIVE_INFINITY,hi=Float.NEGATIVE_INFINITY;for(int p=0;p<b.position();p+=16){float v=b.getFloat(p+axis*4);lo=Math.min(lo,v);hi=Math.max(hi,v);}return hi-lo;}
    private static void integration(int state)throws Exception {
        Constructor<SurfaceChunk> c=SurfaceChunk.class.getDeclaredConstructor(ByteBuffer.class,ByteBuffer.class,ByteBuffer.class,ByteBuffer.class,ByteBuffer.class);c.setAccessible(true);
        SurfaceChunk chunk=c.newInstance(ByteBuffer.allocate(0),ByteBuffer.allocate(8192),ByteBuffer.allocate(SurfaceChunk.BIOME_BYTES),ByteBuffer.allocate(0),ByteBuffer.allocate(0));
        Method reset=SurfaceChunk.class.getDeclaredMethod("reset",int.class,int.class);reset.setAccessible(true);reset.invoke(chunk,0,0);
        Method finish=SurfaceChunk.class.getDeclaredMethod("finishFullBlockUpdate",int.class);finish.setAccessible(true);finish.invoke(chunk,16);
        Method put=SurfaceChunk.class.getDeclaredMethod("putFullBlockState",int.class,int.class,int.class,int.class);put.setAccessible(true);put.invoke(chunk,8,1,8,state);
        ByteBuffer mesh=ByteBuffer.allocate(VanillaBlockModels.maxVertexCount()*16+4096);
        int count=SurfaceMesher.mesh(chunk,0,mesh);
        check(count==VanillaBlockModels.vertexCount(state),"no extra cube around original geometry "+state);
        ByteBuffer light=ByteBuffer.allocate(count*2);MeshLighting.build(chunk,0,null,mesh,count,light);
        check(light.position()==count*2,"light buffer stays two bytes per vertex");
    }
    private static int token(ByteBuffer b,int p){return (b.get(p+12)&255)|((b.get(p+13)&255)<<8)|((b.get(p+14)&255)<<16)|((b.get(p+15)&255)<<24);}
    private static void check(boolean b,String text){if(!b)throw new AssertionError(text);}
}
