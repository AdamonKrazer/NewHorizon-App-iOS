package com.newhorizon.thinclient.world;

import java.nio.ByteBuffer;
import com.newhorizon.thinclient.audio.BiomeSoundRegistry;
import com.newhorizon.thinclient.memory.MemoryBudget;

public final class PrecipitationSelfTest {
    public static void run()throws Exception {
        geometry();surfaces();particles();viewCulling();
        System.out.println("PrecipitationSelfTest passed: all camera directions/near columns, continuous height/UV, roofs, dry/snow biomes, terrain cache invalidation, sand/slabs/water, impact bounce/expiry and steam");
    }
    private static void geometry() {
        Precipitation.Column c=new Precipitation.Column();
        for(int yaw=0;yaw<360;yaw+=15)for(int z=-2;z<=2;z++)for(int x=-2;x<=2;x++) {
            check(Precipitation.column(c,x,z,.5f,65.5f,.5f,yaw,64,false,1,100),"near column retained at every view angle");
            check(c.bottom==64&&c.top==75,"continuous rain reaches exposed terrain");
            check(Float.isFinite(c.rightX)&&Float.isFinite(c.rightZ)&&Math.abs(c.rightX*c.rightX+c.rightZ*c.rightZ-.25)<.00001,"finite one-block strip including center");
            check(Math.abs((c.x-.5)*c.rightX+(c.z-.5)*c.rightZ)<.00001,"strip faces the camera position");
        }
        check(!Precipitation.column(c,0,0,.5f,65,.5f,0,80,false,1,100),"roof above vertical range prevents indoor rain");
        check(Precipitation.column(c,0,0,.5f,65,.5f,0,69,false,1,100)&&c.bottom==69,"ceiling clips rain to roof top");
        check(!Precipitation.column(c,0,0,0,65,0,0,Integer.MIN_VALUE,false,1,100),"unloaded columns skipped");
        check(!Precipitation.column(c,0,0,0,65,0,0,64,false,0,100),"clear weather");
        Precipitation.column(c,-17,31,-17.2f,65,31.3f,0,64,false,1,1e10);float before=c.scroll;
        Precipitation.column(c,-17,31,-17.2f,65,31.3f,0,64,false,1,1e10+.2);
        check(c.scroll!=before&&c.scroll>=0&&c.scroll<1,"long sessions preserve sub-tick texture animation");
        BiomeSoundRegistry.Biome b=new BiomeSoundRegistry.Biome();check(Precipitation.kind(b)==0,"dry biome");
        b.precipitation=true;check(Precipitation.kind(b)==1,"rain biome");b.temperature=0;check(Precipitation.kind(b)==2,"snow biome");
        check(Precipitation.kind(null)==0,"unloaded biome");
        check(Precipitation.COLUMNS*6<=3072,"weather fits bounded vertex capacity");
    }
    private static void surfaces()throws Exception {
        int sand=VanillaBlockTextures.defaultState("sand"),dirt=VanillaBlockTextures.defaultState("dirt"),water=VanillaBlockTextures.defaultState("water"),slab=VanillaBlockTextures.defaultState("oak_slab");
        check(Precipitation.impactHeight(sand,.5,.5)==1&&Precipitation.impactHeight(dirt,.5,.5)==1,"sand and dirt impact on top");
        check(Precipitation.impactHeight(slab,.5,.5)==.5,"bottom slab exact top");
        check(Math.abs(Precipitation.impactHeight(water,.5,.5)-8/9.0)<.001,"water surface impact");
        WorldChunkStore w=new WorldChunkStore(MemoryBudget.lowRamDefaults(),1,16,-1);
        try {
            w.enterDimension("minecraft:overworld",-16,16);
            w.applyVanillaChunkData(-1,0,empty());
            check(w.rainSurface(-1,0)==-16,"empty negative-Y column");
            w.applyVanillaBlockUpdate(-1,-14,0,sand);check(w.rainSurface(-1,0)==-13,"placement invalidates cache");
            for(int i=0;i<1000;i++)check(w.rainSurface(-1,0)==-13,"stable cached column");
            w.applyVanillaBlockUpdate(-1,-8,0,dirt);check(w.rainSurface(-1,0)==-7,"roof invalidates cache");
            w.applyVanillaBlockUpdate(-1,-8,0,0);check(w.rainSurface(-1,0)==-13,"roof removal exposes ground");
            w.applyVanillaBlockUpdate(-1,-12,0,water);check(w.rainSurface(-1,0)==-11,"fluid blocks precipitation");
            w.applyVanillaChunkData(-1,0,empty());check(w.rainSurface(-1,0)==-16,"full chunk replacement invalidates cache");
            w.enterDimension("minecraft:the_nether",0,16);check(w.rainSurface(-1,0)==Integer.MIN_VALUE,"dimension reset discards heights");
        } finally {w.close();}
        SurfaceChunk a=AtmosphereSelfTest.fixture(),copy=AtmosphereSelfTest.fixture();
        check(a.rainHeight(6,7)==2,"fixture cached height");a.putFullBlockState(6,8,7,dirt);copy.copyFrom(a);
        check(copy.rainHeight(6,7)==9,"snapshot copy preserves dirty cache");a.reset(4,4);a.finishFullBlockUpdate(0);check(a.rainHeight(6,7)==0,"slot reuse clears cache");
    }
    private static void particles()throws Exception {
        long now=1_000_000_000L;int sand=VanillaBlockTextures.defaultState("sand");WorldParticles p=new WorldParticles();
        WorldParticles.Particle[] out=new WorldParticles.Particle[512];for(int i=0;i<out.length;i++)out[i]=new WorldParticles.Particle();
        p.rainHit(2.5,1.01,2.5,sand,now);check(p.snapshot(out,now,null,2,1,2)==1&&out[0].type==50,"original rain sprite spawned at contact");
        check(ParticleSprites.frame(50,out[0].data,4)>=124&&ParticleSprites.frame(50,out[0].data,4)<=127,"original droplet variations");
        p.snapshot(out,now+75_000_000L,null,2,1,2);check(out[0].y>1.01,"impact droplet bounces upwards");
        check(p.snapshot(out,now+3_000_000_000L,null,2,1,2)==0,"rain expires");
        p.rainHit(2.5,1.01,2.5,VanillaBlockTextures.defaultState("magma_block"),now);check(p.snapshot(out,now,null,2,1,2)==1&&out[0].type==51,"hot surface emits smoke");
        p.clear();WorldChunkStore w=new WorldChunkStore(MemoryBudget.lowRamDefaults(),1,16,-1);
        try {
            w.enterDimension("minecraft:overworld",0,16);w.applyVanillaChunkData(0,0,empty());w.applyVanillaBlockUpdate(2,0,2,sand);
            p.rainHit(2.5,1.01,2.5,sand,now);
            for(int tick=0;tick<50;tick++){int n=p.snapshot(out,now+tick*50_000_000L,w,2,1,2);for(int i=0;i<n;i++)check(out[i].y>=1,"rain particle never falls through ground");}
        }finally{w.close();}
    }
    private static void viewCulling() {
        com.newhorizon.thinclient.render.WeatherView view=new com.newhorizon.thinclient.render.WeatherView();
        for(int yaw=0;yaw<360;yaw+=15)for(int pitch=-90;pitch<=90;pitch+=15) {
            double sy=Math.sin(Math.toRadians(yaw)),cy=Math.cos(Math.toRadians(yaw));
            double sp=Math.sin(Math.toRadians(pitch)),cp=Math.cos(Math.toRadians(pitch));
            double dx=-sy*cp*5,dy=-sp*5,dz=cy*cp*5;
            view.camera(10,65,-20,yaw,pitch,2.2f,1.4f);
            check(view.visible(10+dx,65+dy,-20+dz,.2,.2,.2),"front rain particle always retained");
            check(!view.visible(10-dx,65-dy,-20-dz,.2,.2,.2),"rain particle behind camera excluded");
            check(view.visible(10,65,-20,.5,10,.5),"column crossing camera never culled into a tunnel");
        }
        view.camera(0,0,0,0,0,1,1);
        check(view.visible(7,0,5,3,.2,.2),"wide strip crossing edge retained");
        check(!view.visible(100,0,5,.2,.2,.2),"offscreen rain excluded before shading");
    }
    private static ByteBuffer empty(){return ByteBuffer.wrap(new byte[]{0,0,0,0,0,0,0,0});}
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
}

