package com.newhorizon.thinclient.inventory;

import com.newhorizon.thinclient.protocol.*;
import java.nio.ByteBuffer;

/** Server cooldowns are authoritative; the client only suppresses redundant uses. */
public final class ItemUseState {
    private final long[] cooldownEnds = new long[2048];
    private long generation;
    public synchronized void readCooldown(ByteBuffer packet) throws ProtocolException {
        int item=VarInts.read(packet), ticks=VarInts.read(packet);
        if(item<0 || ticks<0 || packet.hasRemaining()) throw new ProtocolException("Invalid item cooldown");
        if(item<cooldownEnds.length) cooldownEnds[item]=ticks==0 ? 0 : System.nanoTime()+ticks*50_000_000L;
    }
    public synchronized boolean coolingDown(String material,long now) {
        int id=itemId(material); return id>=0 && cooldownEnds[id]>now;
    }
    public synchronized void clear() { java.util.Arrays.fill(cooldownEnds,0); generation++; }
    public synchronized long generation() { return generation; }
    static int itemId(String material) {
        if(material==null) return -1;
        material=com.newhorizon.thinclient.world.ProjectileKind.materialKey(material);
        switch(material) {
            case "BOW": return 760; case "CROSSBOW": return 1143; case "SHIELD": return 1116;
            case "TRIDENT": return 1139; case "EGG": return 887;
            case "SNOWBALL": return 872; case "ENDER_PEARL": return 952;
            case "SPLASH_POTION": return 1112; case "LINGERING_POTION": return 1115;
            case "EXPERIENCE_BOTTLE": return 1044; case "FIRE_CHARGE": return 1045;
            case "FIREWORK_ROCKET": return 1066; default: return -1;
        }
    }
}
