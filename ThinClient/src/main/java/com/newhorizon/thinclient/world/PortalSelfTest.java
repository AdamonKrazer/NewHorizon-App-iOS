package com.newhorizon.thinclient.world;

public final class PortalSelfTest {
    public static void run() {
        PortalState effect=new PortalState();
        check(effect.advance(true,.05f),"trigger sound once on entry");
        check(Math.abs(effect.progress()-.0125f)<.00001,"vanilla progress per tick");
        for(int i=1;i<80;i++)check(!effect.advance(true,.05f),"no repeated trigger inside");
        check(Math.abs(effect.progress()-1)<.00001,"full effect after 80 ticks");
        effect.advance(true,.05f);check(effect.progress()==1,"saturated effect");
        effect.advance(false,.05f);check(Math.abs(effect.progress()-.95f)<.00001,"vanilla decay per tick");
        for(int i=0;i<20;i++)effect.advance(false,.05f);
        check(effect.progress()==0&&PortalState.opacity(0)==0,"no overlay outside portal");
        check(Math.abs(PortalState.opacity(.5f)-.25f)<.00001&&PortalState.opacity(1)==1,"vanilla fourth-power opacity");
        effect.arrive(10);
        for(int i=0;i<100;i++)check(!effect.advance(true,.05f),"arrival cooldown stays active inside destination portal");
        check(effect.progress()==0,"destination portal cannot re-trigger effect during cooldown");
        for(int i=0;i<11;i++)effect.advance(false,.05f);
        check(effect.advance(true,.05f),"entry re-enabled after leaving and cooldown");
        int portal=VanillaBlockTextures.defaultState("nether_portal"),end=VanillaBlockTextures.defaultState("end_portal");
        check(portal>0&&VanillaBlockTextures.tileId("nether_portal")>=0,"original animated portal texture bundled");
        WorldBlockRaycast.Blocks blocks=(x,y,z)->x==0&&z==0&&y==65?portal:0;
        check(PortalState.touching(blocks,.5,64,.5,1.8),"upper body contact starts effect");
        check(PortalState.touching(blocks,1.2,64,.5,1.8),"side of body intersects portal voxel");
        check(!PortalState.touching(blocks,1.31,64,.5,1.8),"nearby portal alone does not start effect");
        check(!PortalState.touching((x,y,z)->end,.5,64,.5,1.8),"End portal does not use Nether overlay");
        System.out.println("PortalSelfTest passed: contact, original texture, 80-tick buildup, fade, opacity, sound edge and arrival cooldown");
    }
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
}
