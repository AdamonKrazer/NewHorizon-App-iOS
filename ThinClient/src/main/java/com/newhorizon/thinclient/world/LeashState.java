package com.newhorizon.thinclient.world;

import java.nio.ByteBuffer;
import java.util.Arrays;
import com.newhorizon.thinclient.protocol.*;

/** ClientboundSetEntityLink: two signed ints; holder zero means detach, not entity zero. */
public final class LeashState {
    private final int[] mobs=new int[256],holders=new int[256];
    public LeashState(){clear();}
    public synchronized void read(ByteBuffer in)throws ProtocolException {
        BinaryCodec.require(in,8);int mob=in.getInt(),holder=in.getInt();
        if(in.hasRemaining()||mob<0||holder<0||(holder!=0&&mob==holder))throw new ProtocolException("Invalid leash link");
        int free=-1;
        for(int i=0;i<mobs.length;i++) {
            if(mobs[i]==mob){if(holder==0)mobs[i]=-1;else holders[i]=holder;return;}
            if(mobs[i]<0&&free<0)free=i;
        }
        // Preserve links that arrive before the entity spawn. Saturation only drops new visuals.
        if(holder!=0&&free>=0){mobs[free]=mob;holders[free]=holder;}
    }
    public synchronized boolean hasHolder(int id){for(int i=0;i<mobs.length;i++)if(mobs[i]>=0&&holders[i]==id)return true;return false;}
    public synchronized int holder(int mob){for(int i=0;i<mobs.length;i++)if(mobs[i]==mob)return holders[i];return -1;}
    public synchronized void remove(int id){for(int i=0;i<mobs.length;i++)if(mobs[i]==id||holders[i]==id)mobs[i]=-1;}
    public synchronized void clear(){Arrays.fill(mobs,-1);Arrays.fill(holders,-1);}
}
