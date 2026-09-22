package com.newhorizon.thinclient.world;

public final class SwimmingSelfTest {
    public static void run(){
        SwimmingState s=new SwimmingState();
        s.update(true,true,false,true,false,true,true);
        check(!s.swimming,"standing in shallow water must not start swimming");
        s.update(true,true,true,true,false,true,true);
        check(s.swimming&&s.prone,"sprint while submerged enters swimming");
        s.update(true,true,false,true,false,true,true);
        check(s.swimming,"swimming continues at surface when eyes emerge");
        s.update(false,true,true,true,false,true,true);
        check(!s.swimming&&!s.prone,"releasing forward exits swimming");
        s.update(false,false,false,false,false,false,false);
        check(s.prone,"low ceiling retains crawl box");
        s.update(false,false,false,false,false,false,true);
        check(!s.prone,"enough room to crouch ends crawling");
        s.update(true,true,true,true,true,true,true);
        check(!s.swimming&&!s.prone,"flight and riding cannot swim");
        check(SwimmingState.steer(0,30,false,true)<0,"look down dives");
        check(SwimmingState.steer(0,-30,false,true)>0,"look up ascends underwater");
        check(SwimmingState.steer(0,-30,false,false)==0,"surface does not launch swimmer into air");
        check(SwimmingState.steer(0,-30,true,false)>0,"jump permits surfacing");
        EntityAnimation a=new EntityAnimation();a.reset(1,0,0,0,0,0);
        EntityTracker.Renderable e=new EntityTracker.Renderable();
        for(int i=0;i<12;i++)a.swimming(true);
        a.snapshot(e,EntityAnimation.TICK);check(e.swimAmount==1,"full prone blend after twelve ticks");
        a.swimming(false);a.snapshot(e,EntityAnimation.TICK/2);
        check(e.swimAmount>.91f&&e.swimAmount<1,"interpolated exit pose");
        for(int i=0;i<12;i++)a.swimming(false);
        a.snapshot(e,EntityAnimation.TICK);check(e.swimAmount==0,"standing blend restored");
        System.out.println("Swimming tests passed: entry/surface/exit/ceiling/flight, dive steering and tick interpolation");
    }
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
}
