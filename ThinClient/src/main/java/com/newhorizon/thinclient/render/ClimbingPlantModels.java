package com.newhorizon.thinclient.render;

import com.newhorizon.thinclient.world.VanillaBlockTextures;
import java.nio.ByteBuffer;

/** Original 1.20.1 vine/bamboo multipart models, kept in the 16-byte terrain vertex format. */
final class ClimbingPlantModels {
    static final int VINE_FIRST=6837,VINE_LAST=6868,BAMBOO_FIRST=12804,BAMBOO_LAST=12815,SAPLING=12803;
    static final int UNSHADED=1<<17,FOLIAGE=1<<28;
    private static final int VINE=VanillaBlockTextures.tileId("vine"),STALK=VanillaBlockTextures.tileId("bamboo_stalk"),
            SMALL=VanillaBlockTextures.tileId("bamboo_small_leaves"),LARGE=VanillaBlockTextures.tileId("bamboo_large_leaves"),SHOOT=VanillaBlockTextures.tileId("bamboo_stage0");
    private static final int[] ORDER={0,1,2,0,2,3},VINE_BITS={8,16,4,1,2};
    static boolean contains(int state){return vine(state)||state>=SAPLING&&state<=BAMBOO_LAST;}
    static boolean vine(int state){return state>=VINE_FIRST&&state<=VINE_LAST;}
    static int vineMask(int state){int mask=31-(state-VINE_FIRST);return mask==0?31:mask;}
    static int vertexCount(int state){return vine(state)?Integer.bitCount(vineMask(state))*12:state==SAPLING?24:36+((state-BAMBOO_FIRST)%6>=2?24:0);}
    static int append(ByteBuffer out,int state,int x,int y,int z,int biome){
        int count=vertexCount(state);if(out.remaining()<count*16)return 0;
        if(vine(state)){
            int mask=vineMask(state);
            for(int side=0;side<5;side++)if((mask&VINE_BITS[side])!=0){
                quad(out,x,y,z,0,0,.8f,16,16,.8f,2,16,0,0,16,VINE,UNSHADED|FOLIAGE,biome,side);
                quad(out,x,y,z,0,0,.8f,16,16,.8f,3,0,0,16,16,VINE,UNSHADED|FOLIAGE,biome,side);
            }
        }else{
            long offset=seed(x,0,z);float bx=x+offset(offset,0),bz=z+offset(offset,8);
            if(state==SAPLING){leaves(out,bx,y,bz,SHOOT,5);return count;}
            int age=(state-BAMBOO_FIRST)/6,leaf=(state-BAMBOO_FIRST)%6/2,width=2+age;
            float lo=8-width*.5f,hi=8+width*.5f;
            int stripe=variant(x,y,z)*3;
            for(int face=0;face<6;face++)quad(out,bx,y,bz,lo,0,lo,hi,16,hi,face,
                    face<2?13:stripe,face==0?4:0,face<2?13+width:stripe+width,face==0?4+width:face==1?width:16,STALK,0,0,0);
            if(leaf!=0)leaves(out,bx,y,bz,leaf==1?SMALL:LARGE,0);
        }
        return count;
    }
    private static void leaves(ByteBuffer out,float x,float y,float z,int tile,int rotation){
        for(int face=2;face<6;face++)quad(out,x,y,z,face<4?.8f:8,0,face<4?8:.8f,face<4?15.2f:8,16,face<4?8:15.2f,face,0,0,16,16,tile,UNSHADED,0,rotation);
    }
    // Mth.getSeed and LegacyRandomSource, including MultiPartBakedModel's random reseed.
    static long seed(int x,int y,int z){long s=(long)(x*3129871)^(long)z*116129781L^y;return (s*s*42317861L+s*11L)>>16;}
    static float offset(long seed,int shift){return (float)((((seed>>shift)&15)/15f-.5)*.5);}
    private static long nextLong(long seed){long s=(seed^0x5deece66dL)&((1L<<48)-1);s=(s*0x5deece66dL+11)&((1L<<48)-1);int high=(int)(s>>>16);s=(s*0x5deece66dL+11)&((1L<<48)-1);return ((long)high<<32)+(int)(s>>>16);}
    static int variant(int x,int y,int z){return Math.abs((int)nextLong(nextLong(seed(x,y,z))))%4;}
    static int token(int tile,int u,int v,int flags,int biome){
        // Full-tile foliage needs only the endpoint bit of U/V. The spare lower bits
        // retain all seven biome bits, while cocoa/bamboo keep their explicit pixel UVs.
        if((flags&FOLIAGE)!=0){u|=(biome&127)>>3;v|=biome&7;}
        return SurfaceMesher.TEXTURED|(7<<20)|tile|(u<<12)|(v<<23)|flags;
    }
    private static void quad(ByteBuffer out,float bx,float by,float bz,float x0,float y0,float z0,float x1,float y1,float z1,int face,int u0,int v0,int u1,int v1,int tile,int flags,int biome,int rotation){
        for(int corner:ORDER){boolean right=corner==1||corner==2,bottom=corner>=2;float x,y,z;
            if(face==0){x=right?x1:x0;y=y0;z=bottom?z0:z1;}
            else if(face==1){x=right?x1:x0;y=y1;z=bottom?z1:z0;}
            else if(face==2){x=right?x0:x1;y=bottom?y0:y1;z=z0;}
            else if(face==3){x=right?x1:x0;y=bottom?y0:y1;z=z1;}
            else if(face==4){x=x0;y=bottom?y0:y1;z=right?z1:z0;}
            else{x=x1;y=bottom?y0:y1;z=right?z0:z1;}
            if(rotation==4){float old=y;y=16-z;z=old;}
            else if(rotation==5){float old=x;x=8+(x-8)-(z-8);z=8+(old-8)+(z-8);}
            else for(int t=0;t<rotation;t++){float old=x;x=16-z;z=old;}
            out.putFloat(bx+x/16).putFloat(by+y/16).putFloat(bz+z/16);
            int packed=token(tile,right?u1:u0,bottom?v1:v0,flags,biome);
            out.put((byte)packed).put((byte)(packed>>>8)).put((byte)(packed>>>16)).put((byte)(packed>>>24));
        }
    }
    private ClimbingPlantModels(){}
}
