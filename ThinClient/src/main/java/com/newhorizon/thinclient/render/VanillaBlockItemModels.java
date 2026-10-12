package com.newhorizon.thinclient.render;

import java.io.*;
import java.nio.FloatBuffer;
import java.util.*;
import java.util.zip.GZIPInputStream;

/** Offline-resolved original item JSON geometry. Positions and cropped UVs retain the source model. */
final class VanillaBlockItemModels {
    private static final Map<Integer,Model> MODELS=load();
    private static int maximumVertices;
    private static final int[] TRIANGLES={0,1,2,0,2,3};
    private VanillaBlockItemModels() { }
    static int size(){return MODELS.size();}
    static int maxVertices(){return maximumVertices;}
    static int vertexCount(int state){Model m=MODELS.get(state);return m==null?36:m.quads*6;}
    static float[] firstPerson(int state){Model m=MODELS.get(state);return m==null?null:m.firstPerson;}
    static float[] ground(int state){Model m=MODELS.get(state);return m==null?null:m.ground;}
    /** -1 means this state uses a dedicated block-entity or generated renderer. */
    static int append(FloatBuffer out,int state,float[] matrix) {
        Model model=MODELS.get(state);if(model==null)return -1;
        int vertices=model.quads*6;if(out.remaining()<vertices*9)return 0;
        for(int quad=0;quad<model.quads;quad++) {
            int color=model.colors[quad],tile=model.tiles[quad];float shade=model.shades[quad];int base=quad*20;
            float red=((color>>16)&255)/255f*shade,green=((color>>8)&255)/255f*shade,blue=(color&255)/255f*shade;
            for(int vertex:TRIANGLES) {
                int p=base+vertex*3;float x=model.data[p],y=model.data[p+1],z=model.data[p+2];
                float u=Math.max(0,Math.min(1,model.data[base+12+vertex*2]));
                float v=Math.max(0,Math.min(1,model.data[base+13+vertex*2]));
                out.put(matrix[0]*x+matrix[4]*y+matrix[8]*z+matrix[12]);
                out.put(matrix[1]*x+matrix[5]*y+matrix[9]*z+matrix[13]);
                out.put(matrix[2]*x+matrix[6]*y+matrix[10]*z+matrix[14]);
                out.put(red).put(green).put(blue).put(1);
                out.put((tile%32*32+.01f+u*31.98f)/BlockAtlas.WIDTH);
                out.put((tile/32*32+.01f+v*31.98f)/BlockAtlas.HEIGHT);
            }
        }
        return vertices;
    }
    private static Map<Integer,Model> load() {
        Map<Integer,Model> result=new HashMap<Integer,Model>();
        try(InputStream resource=VanillaBlockItemModels.class.getResourceAsStream("/assets/newhorizon/item_models.bin.gz")) {
            if(resource==null)throw new IOException("Missing vanilla item geometry");
            try(DataInputStream in=new DataInputStream(new GZIPInputStream(resource))) {
                if(in.readInt()!=0x4e48494d||in.readInt()!=1)throw new IOException("Item geometry version");
                int count=in.readInt();if(count<1||count>1255)throw new IOException("Item model count");
                for(int i=0;i<count;i++) {
                    int state=in.readInt();String name=in.readUTF();Model m=new Model();
                    if(state<0||state>=24135||!name.matches("[a-z0-9_]+"))throw new IOException("Item geometry key");
                    m.firstPerson=readFloats(in,9);m.ground=readFloats(in,9);m.quads=in.readUnsignedShort();
                    if(m.quads<1||m.quads>256)throw new IOException("Item geometry limit");
                    maximumVertices=Math.max(maximumVertices,m.quads*6);
                    m.tiles=new int[m.quads];m.colors=new int[m.quads];m.shades=new float[m.quads];m.data=new float[m.quads*20];
                    for(int q=0;q<m.quads;q++) {
                        m.tiles[q]=in.readUnsignedShort();m.colors[q]=in.readInt();m.shades[q]=in.readFloat();
                        if(m.tiles[q]>=4096||m.colors[q]<0||m.colors[q]>0xffffff||!Float.isFinite(m.shades[q]))throw new IOException("Item material bounds");
                        float[] face=readFloats(in,20);System.arraycopy(face,0,m.data,q*20,20);
                    }
                    if(result.put(state,m)!=null)throw new IOException("Duplicate item block state");
                }
                if(in.read()!=-1)throw new IOException("Item geometry trailing bytes");
            }
        }catch(IOException e){throw new ExceptionInInitializerError(e);}
        return Collections.unmodifiableMap(result);
    }
    private static float[] readFloats(DataInputStream in,int count)throws IOException {
        float[] result=new float[count];for(int i=0;i<count;i++){result[i]=in.readFloat();if(!Float.isFinite(result[i])||Math.abs(result[i])>1024)throw new IOException("Item geometry numeric bounds");}return result;
    }
    private static final class Model {int quads;int[] tiles,colors;float[] firstPerson,ground,shades,data;}
}