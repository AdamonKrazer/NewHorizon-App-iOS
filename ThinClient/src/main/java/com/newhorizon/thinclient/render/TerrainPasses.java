package com.newhorizon.thinclient.render;
import java.nio.ByteBuffer;
/** Stable in-place triangle partition on the worker. Geometry and light stay paired. */
final class TerrainPasses {
 static final int TRIANGLE_BYTES=3*SurfaceMesher.VERTEX_BYTES;
 static final int CAPACITY=ChunkMeshWorker.BYTES/TRIANGLE_BYTES,BYTES=CAPACITY*4+54;
 private final int[] destinations=new int[CAPACITY];
 private final byte[] geometryScratch=new byte[TRIANGLE_BYTES],lightScratch=new byte[6];
 int partition(ByteBuffer geometry,ByteBuffer light,int vertices){
  if(vertices%3!=0||vertices/3>CAPACITY)throw new IllegalArgumentException("Triangle count");
  int count=vertices/3,opaque=0;
  for(int i=0;i<count;i++)if(!liquid(geometry,i*TRIANGLE_BYTES+12))opaque++;
  if(opaque==0||opaque==count)return opaque*3;
  int solidAt=0,liquidAt=opaque;
  for(int i=0;i<count;i++)destinations[i]=liquid(geometry,i*TRIANGLE_BYTES+12)?liquidAt++:solidAt++;
  for(int i=0;i<count;i++){
   if(destinations[i]<0)continue;
   if(destinations[i]==i){destinations[i]=-1;continue;}
   for(int k=0;k<TRIANGLE_BYTES;k++)geometryScratch[k]=geometry.get(i*TRIANGLE_BYTES+k);
   for(int k=0;k<6;k++)lightScratch[k]=light.get(i*6+k);
   int from=i;
   do{int to=destinations[from];destinations[from]=-1;
    for(int k=0;k<TRIANGLE_BYTES;k++){int pos=to*TRIANGLE_BYTES+k;byte old=geometry.get(pos);geometry.put(pos,geometryScratch[k]);geometryScratch[k]=old;}
    for(int k=0;k<6;k++){int pos=to*6+k;byte old=light.get(pos);light.put(pos,lightScratch[k]);lightScratch[k]=old;}
    from=to;
   }while(from!=i);
  }
  return opaque*3;
 }
 static boolean liquid(ByteBuffer g,int p){
  int alpha=g.get(p+3)&255,token=(g.get(p)&255)|((g.get(p+1)&255)<<8)|((g.get(p+2)&255)<<16)|(alpha<<24);
  if(VanillaBlockModels.isToken(token))return (VanillaBlockModels.descriptor(token&VanillaBlockModels.CORNER_MASK)&262144)!=0;
  if((token&1073741824)!=0&&alpha<128)return (token&262144)!=0;
  return alpha<=2;
 }
}
