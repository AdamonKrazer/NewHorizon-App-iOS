package com.newhorizon.thinclient.world;

/** Spectators are hidden from normal players, and render only a head to other spectators. */
public final class PlayerVisibility {
    public static int filter(EntityTracker.Renderable[] entities,int count,PlayerListState players,boolean spectatorViewer) {
        for(int i=0;i<count;) {
            EntityTracker.Renderable e=entities[i];
            e.spectator=(e.player||e.type==122)&&players!=null&&players.isSpectator(e.profileId);
            if(e.spectator&&!spectatorViewer) {
                // Swap, rather than copy, so reusable snapshot objects remain distinct.
                entities[i]=entities[--count];entities[count]=e;
            } else i++;
        }
        return count;
    }
    public static boolean part(boolean spectator,String path){return !spectator||path.equals("/head")||path.equals("/hat");}
    private PlayerVisibility(){}
}
