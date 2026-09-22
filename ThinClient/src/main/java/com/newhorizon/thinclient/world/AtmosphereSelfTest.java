package com.newhorizon.thinclient.world;

import com.newhorizon.thinclient.render.MeshLighting;
import com.newhorizon.thinclient.render.SurfaceMesher;
import java.nio.ByteBuffer;

public final class AtmosphereSelfTest {
    public static void run() {
        AtmosphereState a=new AtmosphereState();EnvironmentState.Snapshot t=new EnvironmentState.Snapshot();
        float[] day=new float[3],night=new float[3],cave=new float[3],torch=new float[3];
        t.dayTime=6000;a.sample(t,"minecraft:overworld",null,0,4,90);a.light(15,0,day);a.light(0,0,cave);
        check(a.daylight==1&&a.sunset[3]==0,"noon sky without sunrise tint");
        check(day[0]>.9&&cave[0]<.15,"outdoor skylight and dark cave remain distinct");
        check(a.fogEnd==64&&a.fogStart==48,"fog finishes inside resident chunk coverage");
        t.dayTime=18000;a.sample(t,"minecraft:overworld",null,0,4,90);a.light(15,0,night);a.light(0,12,torch);
        check(night[2]>night[0]&&night[0]<day[0]*.5,"night sky light is dim and blue");
        check(torch[0]>torch[2]&&torch[0]>night[0],"block light remains warm and visible at night");
        t.dayTime=12000;a.sample(t,"minecraft:overworld",null,0,2,90);
        check(a.sunset[3]>.1&&a.sunset[0]>a.sunset[2]&&a.fogEnd==32,"sunset color and shorter view distance");
        t.dayTime=6000;a.sample(t,"minecraft:overworld",null,0,4,0);float clear=a.sky[2];
        t.rain=t.thunder=1;a.sample(t,"minecraft:overworld",null,0,4,0);check(a.sky[2]<clear*.5,"storm darkens sky");
        a.sample(t,"minecraft:overworld",null,2,4,0);check(a.sphericalFog&&a.fogEnd==1,"dense lava fog");
        a.sample(t,"minecraft:the_nether",null,0,4,0);check(a.nether&&a.ambient==.1f&&a.fogEnd==32,"Nether ambient and close fog");
        a.sample(t,"minecraft:the_end",null,0,4,0);check(a.end&&a.sunset[3]==0,"End has no sunset");
        lightMesh();
        System.out.println("Atmosphere tests passed: day/night, cave/torch light, sunset, weather, view bounds, light-aware greedy faces and chunk-border lighting");
    }
    public static SurfaceChunk fixture() {
        SurfaceChunk c=new SurfaceChunk(ByteBuffer.allocate(0),ByteBuffer.allocate(8192),ByteBuffer.allocate(SurfaceChunk.BIOME_BYTES),ByteBuffer.allocate(SurfaceChunk.LIGHT_BYTES),ByteBuffer.allocate(0));
        c.reset(0,0);c.finishFullBlockUpdate(16);c.clearLightData();
        c.putFullBlockState(6,1,7,1);c.putFullBlockState(7,1,7,1);
        ByteBuffer sky=ByteBuffer.allocate(2048),block=ByteBuffer.allocate(2048);
        nibble(sky,6,2,7,15);nibble(sky,7,2,7,3);nibble(block,7,2,7,12);
        c.putLightSection(true,1,sky);c.putLightSection(false,1,block);return c;
    }
    private static void lightMesh() {
        SurfaceChunk c=fixture();ByteBuffer vertices=ByteBuffer.allocate(1024*64),lights=ByteBuffer.allocate(8192);
        int count=SurfaceMesher.mesh(c,0,vertices);MeshLighting.build(c,0,null,vertices,count,lights);
        int skyFaces=0,torchFaces=0;
        for(int v=0;v<count;v+=6)if(((vertices.get(v*16+14)&255)>>>4&7)==1) {
            int s=lights.get(v*2)&255,b=lights.get(v*2+1)&255;
            if(s==15&&b==0)skyFaces++;if(s==3&&b==12)torchFaces++;
        }
        check(skyFaces==1&&torchFaces==1,"greedy rectangles must not hide a light boundary");
        check(lights.position()==count*2&&vertices.position()==count*16,"bounded sidecar keeps geometry format");
        SurfaceChunk east=fixture();east.chunkX=1;c.putFullBlockState(15,1,5,1);
        ByteBuffer boundary=ByteBuffer.allocate(2048);nibble(boundary,0,1,5,11);east.putLightSection(false,1,boundary);
        SurfaceChunk[] near=new SurfaceChunk[9];near[5]=east;
        count=SurfaceMesher.mesh(c,0,vertices,near);MeshLighting.build(c,0,near,vertices,count,lights);
        boolean found=false;
        for(int v=0;v<count;v+=6)if(vertices.getFloat(v*16)==16&&((vertices.get(v*16+14)&255)>>>4&7)==5) {
            check((lights.get(v*2+1)&255)==11,"face samples the adjacent chunk light");found=true;
        }
        check(found,"boundary face fixture emitted");
    }
    private static void nibble(ByteBuffer bytes,int x,int y,int z,int value){int i=(y<<8)|(z<<4)|x,p=i>>>1,old=bytes.get(p)&255;bytes.put(p,(byte)((i&1)==0?(old&240)|value:(old&15)|(value<<4)));}
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    private AtmosphereSelfTest() { }
}
