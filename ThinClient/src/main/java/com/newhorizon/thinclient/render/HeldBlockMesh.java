package com.newhorizon.thinclient.render;

import com.newhorizon.thinclient.world.VanillaBlockTextures;
import java.nio.FloatBuffer;

/** Textured block items need only 36 vertices, rather than a cuboid per texture pixel. */
public final class HeldBlockMesh {
    private static final float[][][] FACES={
        {{0,0,0},{1,0,1},{1,0,0},{0,0,0},{0,0,1},{1,0,1}},
        {{0,1,0},{1,1,0},{1,1,1},{0,1,0},{1,1,1},{0,1,1}},
        {{0,0,0},{1,1,0},{0,1,0},{0,0,0},{1,0,0},{1,1,0}},
        {{0,0,1},{0,1,1},{1,1,1},{0,0,1},{1,1,1},{1,0,1}},
        {{0,0,0},{0,1,0},{0,1,1},{0,0,0},{0,1,1},{0,0,1}},
        {{1,0,0},{1,0,1},{1,1,1},{1,0,0},{1,1,1},{1,1,0}}};
    private HeldBlockMesh() { }
    public static int append(FloatBuffer out,int state,float[] m) {
        if(out.remaining()<36*9)return 0;
        for(int face=0;face<6;face++) {
            int descriptor=VanillaBlockTextures.face(state,face),tile=descriptor&4095,tint=(descriptor>>14)&7;
            int color=tint==1?0x7cbd6b:tint==2?0x48b518:tint==3?0x3f76e4:tint==4?0x619961:tint==5?0x80a755:0xffffff;
            float light=face==0?.5f:face==1?1f:face<4?.8f:.6f;
            if((descriptor&(1<<19))!=0)light=1;
            for(float[] p:FACES[face]) {
                float x=p[0],y=p[1],z=p[2];
                float u=face<2?x:face==2?1-x:face==3?x:face==4?z:1-z;
                float v=face==0?1-z:face==1?z:1-y;
                for(int i=0;i<((descriptor>>12)&3);i++){float old=u;u=1-v;v=old;}
                out.put(m[0]*x+m[4]*y+m[8]*z+m[12]).put(m[1]*x+m[5]*y+m[9]*z+m[13]).put(m[2]*x+m[6]*y+m[10]*z+m[14]);
                out.put(((color>>16)&255)/255f*light).put(((color>>8)&255)/255f*light).put((color&255)/255f*light).put(1);
                out.put((tile%32*32+.01f+u*31.98f)/BlockAtlas.WIDTH).put((tile/32*32+.01f+v*31.98f)/BlockAtlas.HEIGHT);
            }
        }
        return 36;
    }
}
