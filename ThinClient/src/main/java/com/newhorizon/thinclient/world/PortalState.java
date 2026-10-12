package com.newhorizon.thinclient.world;

import com.newhorizon.thinclient.audio.VanillaSoundCatalog;

/** LocalPlayer portal animation only; the server continues to own travel and cooldown. */
public final class PortalState {
    private float progress, cooldown, cooldownDuration;
    private long previous;
    public synchronized void clear() { progress=cooldown=cooldownDuration=0; previous=0; }
    public synchronized void arrive(int ticks) { cooldown=cooldownDuration=Math.max(0,ticks)/20f; }
    public synchronized float progress() { return progress; }
    public synchronized boolean update(boolean inside, long now) {
        float seconds=previous==0?.05f:Math.max(0,Math.min(.1f,(now-previous)/1_000_000_000f));
        previous=now;
        return advance(inside,seconds);
    }
    synchronized boolean advance(boolean inside, float seconds) {
        boolean entering=inside && cooldown<=0;
        boolean trigger=entering && progress==0;
        // Vanilla adds .0125 each tick and removes .05 after leaving.
        progress=Math.max(0,Math.min(1,progress+(entering?.25f:-1f)*seconds));
        if(cooldown>0) {
            if(inside)cooldown=cooldownDuration;
            cooldown=Math.max(0,cooldown-seconds);
        }
        return trigger;
    }
    public static float opacity(float value) {
        if(value<=0)return 0;
        return value>=1?1:.2f+.8f*value*value*value*value;
    }
    public static boolean touching(WorldBlockRaycast.Blocks world,double x,double y,double z,double height) {
        for(int by=(int)Math.floor(y+.001);by<=(int)Math.floor(y+height-.001);by++)
            for(int bz=(int)Math.floor(z-.299);bz<=(int)Math.floor(z+.299);bz++)
                for(int bx=(int)Math.floor(x-.299);bx<=(int)Math.floor(x+.299);bx++)
                    if("minecraft:nether_portal".equals(VanillaSoundCatalog.load().blockName(world.blockStateAt(bx,by,bz))))return true;
        return false;
    }
}
