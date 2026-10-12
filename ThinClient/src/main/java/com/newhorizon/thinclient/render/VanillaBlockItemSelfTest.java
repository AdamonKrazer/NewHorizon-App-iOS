package com.newhorizon.thinclient.render;

import com.newhorizon.thinclient.world.VanillaBlockTextures;
import java.nio.FloatBuffer;

/** All model buffers are checked, plus dimensions which generic cube proxies cannot satisfy. */
public final class VanillaBlockItemSelfTest {
    private static final float[] IDENTITY={1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1};
    public static void run() {
        check(VanillaBlockItemModels.size()==677,"all original JSON and special block item models");
        check(HeldBlockMesh.maxVertices()==288,"bounded largest item model");
        FloatBuffer vertices=FloatBuffer.allocate(HeldBlockMesh.maxVertices()*9);int checked=0;
        for(int id=0;id<1255;id++) {
            String name=DroppedItemRenderer.name(id);int state=VanillaBlockTextures.defaultState(name);
            if(state<0||HeldBlockMesh.firstPersonTransform(state)==null)continue;
            vertices.clear();int count=HeldBlockMesh.append(vertices,state,IDENTITY);checked++;
            check(count==HeldBlockMesh.vertexCount(state)&&vertices.position()==count*9,"complete item mesh "+name);
            for(int i=0;i<count;i++) {
                for(int c=0;c<9;c++)check(Float.isFinite(vertices.get(i*9+c)),"finite item vertex "+name);
                check(vertices.get(i*9+7)>=0&&vertices.get(i*9+7)<=1&&vertices.get(i*9+8)>=0&&vertices.get(i*9+8)<=1,"atlas UV bounds "+name);
            }
            FloatBuffer shortBuffer=FloatBuffer.allocate(count*9-1);
            check(HeldBlockMesh.append(shortBuffer,state,IDENTITY)==0&&shortBuffer.position()==0,"atomic item buffer cap "+name);
        }
        check(checked==677,"all item registry models exercised");
        bounds("oak_slab",0,0,0,1,.5f,1);
        bounds("snow",0,0,0,1,.125f,1);
        bounds("white_carpet",0,0,0,1,.0625f,1);
        bounds("cactus",0,0,0,1,1,1);
        bounds("chest",1f/16,0,1f/16,15f/16,14f/16,1);
        bounds("red_bed",0,0,-1,1,9f/16,1);
        bounds("skeleton_skull",.25f,0,.25f,.75f,.5f,.75f);
        bounds("conduit",5f/16,5f/16,5f/16,11f/16,11f/16,11f/16);
        int stairs=VanillaBlockTextures.defaultState("oak_stairs");
        check(HeldBlockMesh.vertexCount(stairs)>36,"stairs retain their two source elements");
        int fence=VanillaBlockTextures.defaultState("oak_fence");
        check(HeldBlockMesh.vertexCount(fence)>36,"inventory fence includes rails rather than placed single post");
        check(HeldItemTextureRoute.select("torch")==HeldItemTextureRoute.GENERATED,"torch item remains original generated sprite");
        int[] torch=VanillaItemSprites.load("torch",null);int visible=0;
        for(int p:torch)if(p!=0)visible++;
        check(visible>0&&visible<128,"torch icon keeps transparent silhouette");
        System.out.println("Vanilla block-item models passed: 677 source models, slab/snow/carpet/cactus bounds, stairs/fence topology, UVs and atomic buffers.");
    }
    private static void bounds(String name,float x,float y,float z,float X,float Y,float Z) {
        FloatBuffer vertices=FloatBuffer.allocate(HeldBlockMesh.maxVertices()*9);
        int count=HeldBlockMesh.append(vertices,VanillaBlockTextures.defaultState(name),IDENTITY);
        float[] min={Float.POSITIVE_INFINITY,Float.POSITIVE_INFINITY,Float.POSITIVE_INFINITY};
        float[] max={Float.NEGATIVE_INFINITY,Float.NEGATIVE_INFINITY,Float.NEGATIVE_INFINITY};
        for(int i=0;i<count;i++)for(int axis=0;axis<3;axis++){float value=vertices.get(i*9+axis);min[axis]=Math.min(min[axis],value);max[axis]=Math.max(max[axis],value);}
        float[] wanted={x,y,z,X,Y,Z};
        for(int axis=0;axis<3;axis++)check(Math.abs(min[axis]-wanted[axis])<.00001f&&Math.abs(max[axis]-wanted[axis+3])<.00001f,"source model bounds "+name+" axis "+axis+" got "+min[axis]+".."+max[axis]);
    }
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
}