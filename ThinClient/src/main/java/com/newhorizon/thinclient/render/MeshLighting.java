package com.newhorizon.thinclient.render;

import com.newhorizon.thinclient.world.SurfaceChunk;
import java.nio.ByteBuffer;

/** Two quarter-level light bytes per vertex; geometry and VBO budgets stay unchanged. */
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
    /** Merge only a uniform light plane; merging across a gradient loses interior corners. */
    static boolean uniform(SurfaceChunk c,SurfaceChunk[] neighbors,int x,int y,int z,int face,int light) {
        x+=DX[face];y+=DY[face];z+=DZ[face];
        for(int a=-1;a<=1;a++)for(int b=-1;b<=1;b++) {
            int sx=x+(face<4?a:0),sy=y+(face>=2?b:0),sz=z+(face<2?b:face>=4?a:0);
            if(at(c,neighbors,sx,sy,sz)!=light)return false;
        }
        return true;
    }
    private static boolean opaque(SurfaceChunk c,SurfaceChunk[] near,int x,int y,int z) {
        int dx=x>>4,dz=z>>4;
        if(dx!=0||dz!=0)c=near!=null&&dx>=-1&&dx<=1&&dz>=-1&&dz<=1?near[(dz+1)*3+dx+1]:null;
        return c!=null && y>=0 && y<c.fullBlockHeight()
                && com.newhorizon.thinclient.world.BlockStatePhysics.fullyOccludesUnitCube(c.fullBlockState(x&15,y,z&15));
    }
    private static int smooth(SurfaceChunk c,SurfaceChunk[] near,float x,float y,float z,int face,int fallback) {
        // Sample the four cell centers around this point in the exposed face plane.
        float u=face<4?x:z,v=face<2?z:y;
        int iu=(int)Math.floor(u-.5f),iv=(int)Math.floor(v-.5f);
        float fu=u-.5f-iu,fv=v-.5f-iv,sky=0,block=0,weight=0;
        int normal=(int)Math.floor((face<2?y:face<4?z:x)+(face%2==0?-.01f:.01f));
        for(int a=0;a<2;a++)for(int b=0;b<2;b++) {
            int sx=face<4?iu+a:normal,sy=face<2?normal:iv+b,sz=face<2?iv+b:face<4?normal:iu+a;
            if(opaque(c,near,sx,sy,sz))continue;
            float w=(a==0?1-fu:fu)*(b==0?1-fv:fv);
            int light=at(c,near,sx,sy,sz);sky+=(light&15)*w;block+=(light>>>4)*w;weight+=w;
        }
        if(weight<.001f)return ((fallback&15)*4)|((fallback>>>4)*4<<8);
        return Math.round(sky*4/weight)|(Math.round(block*4/weight)<<8);
    }
    public static void build(SurfaceChunk chunk,int minY,SurfaceChunk[] neighbors,ByteBuffer geometry,int vertices,ByteBuffer out) {
        if(vertices%6!=0||out.capacity()<vertices*2)throw new IllegalArgumentException("Light mesh bounds");
        out.clear();
        for(int v=0;v<vertices;v+=6) {
            int base=v*SurfaceMesher.VERTEX_BYTES;
            int token=(geometry.get(base+12)&255)|((geometry.get(base+13)&255)<<8)|((geometry.get(base+14)&255)<<16)|((geometry.get(base+15)&255)<<24);
            boolean model=VanillaBlockModels.isToken(token);
            if(model)token=SurfaceMesher.TEXTURED|VanillaBlockModels.descriptor(token&VanillaBlockModels.CORNER_MASK)|(VanillaBlockModels.lightFace(token&VanillaBlockModels.CORNER_MASK)<<20);
            boolean textured=model||((token&SurfaceMesher.TEXTURED)!=0&&(token>>>24)<128);
            int face=textured?(token>>>20)&7:6;
            if(face>6)face=6;
            float x=0,y=0,z=0;
            for(int n=0;n<6;n++){int p=(v+n)*SurfaceMesher.VERTEX_BYTES;x+=geometry.getFloat(p);y+=geometry.getFloat(p+4);z+=geometry.getFloat(p+8);}
            int lx=(int)Math.floor(x/6+DX[face]*.01f)-chunk.chunkX*16;
            int ly=(int)Math.floor(y/6+DY[face]*.01f)-minY;
            int lz=(int)Math.floor(z/6+DZ[face]*.01f)-chunk.chunkZ*16;
            int light=at(chunk,neighbors,lx,ly,lz);
            if(textured&&(token&524288)!=0)light=255;
            for(int n=0;n<6;n++) {
                int p=(v+n)*SurfaceMesher.VERTEX_BYTES;
                int packed=((light&15)*4)|((light>>>4)*4<<8);
                if(textured&&face<6&&(token&524288)==0)
                    packed=smooth(chunk,neighbors,geometry.getFloat(p)-chunk.chunkX*16,
                            geometry.getFloat(p+4)-minY,geometry.getFloat(p+8)-chunk.chunkZ*16,face,light);
                out.put((byte)packed).put((byte)(packed>>>8));
            }
        }
    }
    private MeshLighting() { }
}
