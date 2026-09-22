package com.newhorizon.thinclient.render;

import java.nio.ByteBuffer;
import com.newhorizon.thinclient.world.VanillaBlockTextures;

/** Vanilla 1.20.1 cocoa_stage0..2.json, including the two-sided attachment stem. */
final class CocoaModel {
    static final int FIRST=7419,LAST=7430,VERTICES=48;
    private static final int[] TILES={VanillaBlockTextures.tileId("cocoa_stage0"),VanillaBlockTextures.tileId("cocoa_stage1"),VanillaBlockTextures.tileId("cocoa_stage2")};
    private static final int[] TURNS={2,0,1,3};
    static boolean contains(int state){return state>=FIRST&&state<=LAST;}
    static int append(ByteBuffer out,int state,float x,float y,float z) {
        int stage=(state-FIRST)/4,turns=TURNS[(state-FIRST)&3],size=4+stage*2;
        for(int face=0;face<6;face++)quad(out,x,y,z,6-stage,7-stage*2,11-stage*2,10+stage,12,15,
                face,face<2?0:stage==2?8:11-stage*2,face<2?0:4,face<2?size:stage==2?16:15,face<2?size:9+stage*2,TILES[stage],turns);
        quad(out,x,y,z,8,12,12,8,16,16,4,12,0,16,4,TILES[stage],turns);
        quad(out,x,y,z,8,12,12,8,16,16,5,16,0,12,4,TILES[stage],turns);
        return VERTICES;
    }
    private static final int[] ORDER={0,1,2,0,2,3};
    private static void quad(ByteBuffer out,float bx,float by,float bz,float x0,float y0,float z0,float x1,float y1,float z1,int face,int u0,int v0,int u1,int v1,int tile,int turns){
        // CW outside, matching the terrain pass. UVs follow FaceBakery's face vertices.
        for(int corner:ORDER){boolean right=corner==1||corner==2,bottom=corner>=2;float x,y,z;
            if(face==0){x=right?x1:x0;y=y0;z=bottom?z0:z1;}
            else if(face==1){x=right?x1:x0;y=y1;z=bottom?z1:z0;}
            else if(face==2){x=right?x0:x1;y=bottom?y0:y1;z=z0;}
            else if(face==3){x=right?x1:x0;y=bottom?y0:y1;z=z1;}
            else if(face==4){x=x0;y=bottom?y0:y1;z=right?z1:z0;}
            else {x=x1;y=bottom?y0:y1;z=right?z0:z1;}
            for(int t=0;t<turns;t++){float old=x;x=16-z;z=old;}
            out.putFloat(bx+x/16).putFloat(by+y/16).putFloat(bz+z/16);
            int token=SurfaceMesher.TEXTURED|(7<<20)|tile|((right?u1:u0)<<12)|((bottom?v1:v0)<<23);
            out.put((byte)token).put((byte)(token>>>8)).put((byte)(token>>>16)).put((byte)(token>>>24));
        }
    }
    private CocoaModel(){}
}
