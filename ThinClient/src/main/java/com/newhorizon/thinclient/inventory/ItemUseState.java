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
        return ItemInteractionCatalog.protocolId(material);
    }
}
