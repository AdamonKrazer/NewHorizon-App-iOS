package com.newhorizon.thinclient.render;

import java.io.*;
import java.nio.ByteBuffer;
import java.util.zip.GZIPInputStream;
import com.newhorizon.thinclient.world.VanillaBlockTextures;

/** Original offline-baked block models shared by all chunks; no allocations while meshing. */
public final class VanillaBlockModels {
    public static final int CORNER_MASK=0xfffff,MODEL_TOKEN=0x80000000;
    private static final int[] ORDER={0,1,2,0,2,3};
    private static final float[] LIGHT={.5f,1,.8f,.8f,.6f,.6f};
    private static final Data DATA=load();
    private VanillaBlockModels() { }
    public static boolean contains(int state){return state>=0&&state<DATA.states.length&&DATA.states[state]>=0;}
    public static boolean isToken(int token){return (token&0xc0000000)==MODEL_TOKEN;}
    public static int route(int state){return state>=0&&state<DATA.states.length?DATA.states[state]:-1;}
    public static int stateCount(){return DATA.states.length;}
    public static int maxVertexCount(){int max=36;for(int i=0;i<DATA.offsets.length-1;i++)max=Math.max(max,(DATA.offsets[i+1]-DATA.offsets[i])*6);return max;}
    public static int modelCount(){return DATA.offsets.length-1;}
    public static int cornerCount(){return DATA.materials.length*4;}
    public static int descriptor(int corner){return DATA.materials[corner/4];}
    public static boolean shaded(int corner){return DATA.shade[corner/4]!=0;}
    public static int lightFace(int corner){return DATA.lightFace[corner/4];}
    public static float shade(int corner){return shaded(corner)?LIGHT[lightFace(corner)]:1;}
    public static float coordinate(int corner,int axis){return DATA.vertices[corner*5+axis];}
    public static float textureU(int corner){return coordinate(corner,3);}
    public static float textureV(int corner){return coordinate(corner,4);}
    public static int vertexCount(int state){int model=route(state);return model<0?0:(DATA.offsets[model+1]-DATA.offsets[model])*6;}
    public static int token(int corner,int biome){
        if(corner<0||corner>=cornerCount()||biome<0||biome>127)throw new IllegalArgumentException("Model token bounds");
        return MODEL_TOKEN|corner|(biome<<20);
    }
    /** Returns -1 without writing when the complete block will not fit. Cull mask uses six vanilla directions. */
    public static int append(ByteBuffer out,int state,float x,float y,float z,int biome,int culledFaces) {
        int model=route(state);if(model<0)return 0;
        int start=DATA.offsets[model],end=DATA.offsets[model+1],quads=0;
        for(int face=start;face<end;face++)if(visible(face,culledFaces))quads++;
        if(out.remaining()<quads*6*SurfaceMesher.VERTEX_BYTES)return -1;
        for(int face=start;face<end;face++)if(visible(face,culledFaces))for(int v:ORDER){
            int corner=face*4+v,p=corner*5;
            out.putFloat(x+DATA.vertices[p]).putFloat(y+DATA.vertices[p+1]).putFloat(z+DATA.vertices[p+2]);
            int token=MODEL_TOKEN|corner|(biome<<20);
            out.put((byte)token).put((byte)(token>>>8)).put((byte)(token>>>16)).put((byte)(token>>>24));
        }
        return quads*6;
    }
    private static boolean visible(int face,int mask){int cull=DATA.cull[face];return cull<0||(mask&(1<<cull))==0;}
    private static Data load(){
        try(InputStream resource=VanillaBlockModels.class.getResourceAsStream("/assets/newhorizon/block_models.bin.gz")){
            if(resource==null)throw new IOException("Missing original block models");
            DataInputStream in=new DataInputStream(new GZIPInputStream(resource));
            if(in.readInt()!=0x4e48424d||in.readInt()!=1)throw new IOException("Block model version");
            int states=in.readInt(),models=in.readInt();
            if(states!=24135||models<1||models>24135)throw new IOException("Block model counts");
            Data d=new Data();d.states=new int[states];d.offsets=new int[models+1];
            for(int i=0;i<states;i++){int route=in.readInt();if(route< -2||route>=models)throw new IOException("Block model route");d.states[i]=route;}
            // The compact export is bounded before allocating the final arrays.
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream records=new DataOutputStream(bytes);
            int faces=0;
            for(int m=0;m<models;m++){
                d.offsets[m]=faces;int count=in.readInt();
                if(count<0||count>512||faces+count>262144)throw new IOException("Block model face bounds");
                for(int f=0;f<count;f++){
                    int descriptor=in.readInt(),cull=in.readUnsignedByte(),shade=in.readUnsignedByte(),light=in.readUnsignedByte(),padding=in.readUnsignedByte();
                    if((descriptor&~0xfffff)!=0||(descriptor&4095)>=VanillaBlockTextures.tileCount()||(cull>5&&cull!=255)||shade>1||light>5||padding!=0)throw new IOException("Block model material");
                    records.writeInt(descriptor);records.writeByte(cull);records.writeByte(shade);records.writeByte(light);records.writeByte(0);
                    for(int k=0;k<20;k++){float v=in.readFloat();if(!Float.isFinite(v)||Math.abs(v)>32)throw new IOException("Block model vertex");records.writeFloat(v);}
                }
                faces+=count;
            }
            d.offsets[models]=faces;if(in.read()!=-1)throw new IOException("Trailing block model data");
            d.materials=new int[faces];d.cull=new byte[faces];d.shade=new byte[faces];d.lightFace=new byte[faces];d.vertices=new float[faces*20];
            DataInputStream packed=new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()));
            for(int f=0;f<faces;f++){
                d.materials[f]=packed.readInt();d.cull[f]=packed.readByte();d.shade[f]=packed.readByte();d.lightFace[f]=packed.readByte();packed.readByte();
                for(int k=0;k<20;k++)d.vertices[f*20+k]=packed.readFloat();
            }
            System.out.println("[NH-MODELS] states="+states+" sharedModels="+models+" faces="+faces+" retainedBytes="+(states*4+(models+1)*4+faces*87));return d;
        }catch(IOException e){throw new ExceptionInInitializerError(e);}
    }
    private static final class Data {int[] states,offsets,materials;byte[] cull,shade,lightFace;float[] vertices;}
}