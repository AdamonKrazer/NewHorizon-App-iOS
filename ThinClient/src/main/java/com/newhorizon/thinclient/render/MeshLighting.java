package com.newhorizon.thinclient.render;

import com.newhorizon.thinclient.world.SurfaceChunk;
import java.nio.ByteBuffer;

/** Two light nibbles per vertex in a separate bounded VBO; geometry stays 16 bytes. */
public final class MeshLighting {
    private static final int[] DX={0,0,0,0,-1,1,0},DY={-1,1,0,0,0,0,0},DZ={0,0,-1,1,0,0,0};
    static int at(SurfaceChunk center,SurfaceChunk[] neighbors,int x,int y,int z) {
        SurfaceChunk chunk=center;int dx=x>>4,dz=z>>4;
        if(dx!=0||dz!=0)chunk=neighbors!=null&&dx>=-1&&dx<=1&&dz>=-1&&dz<=1?neighbors[(dz+1)*3+dx+1]:null;
        if(chunk==null)return 15;
        return chunk.light(true,x&15,y,z&15)|(chunk.light(false,x&15,y,z&15)<<4);
    }
    static int face(SurfaceChunk chunk,SurfaceChunk[] neighbors,int x,int y,int z,int face) {
        return at(chunk,neighbors,x+DX[face],y+DY[face],z+DZ[face]);
    }
    public static void build(SurfaceChunk chunk,int minY,SurfaceChunk[] neighbors,ByteBuffer geometry,int vertices,ByteBuffer out) {
        if(vertices%6!=0||out.capacity()<vertices*2)throw new IllegalArgumentException("Light mesh bounds");
        out.clear();
        for(int v=0;v<vertices;v+=6) {
            int base=v*SurfaceMesher.VERTEX_BYTES;
            int token=(geometry.get(base+12)&255)|((geometry.get(base+13)&255)<<8)|((geometry.get(base+14)&255)<<16)|((geometry.get(base+15)&255)<<24);
            boolean textured=(token&SurfaceMesher.TEXTURED)!=0&&(token>>>24)<128;
            int face=textured?(token>>>20)&7:6;
            if(face>6)face=6;
            float x=0,y=0,z=0;
            for(int n=0;n<6;n++){int p=(v+n)*SurfaceMesher.VERTEX_BYTES;x+=geometry.getFloat(p);y+=geometry.getFloat(p+4);z+=geometry.getFloat(p+8);}
            int lx=(int)Math.floor(x/6+DX[face]*.01f)-chunk.chunkX*16;
            int ly=(int)Math.floor(y/6+DY[face]*.01f)-minY;
            int lz=(int)Math.floor(z/6+DZ[face]*.01f)-chunk.chunkZ*16;
            int light=at(chunk,neighbors,lx,ly,lz);
            if(textured&&(token&524288)!=0)light=255;
            for(int n=0;n<6;n++)out.put((byte)(light&15)).put((byte)(light>>>4));
        }
    }
    private MeshLighting() { }
}
