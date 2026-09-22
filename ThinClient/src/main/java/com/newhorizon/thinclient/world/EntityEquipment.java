package com.newhorizon.thinclient.world;

import com.newhorizon.thinclient.protocol.*;
import java.nio.ByteBuffer;
import java.util.Arrays;

/** Six protocol equipment slots; absent updates retain the previous stack. */
public final class EntityEquipment {
    public static final int BOW=760; // protocol 763 registry
    public final int[] items=new int[6];
    public final String[] tags=new String[6];
    public EntityEquipment(){clear();}
    public void clear(){Arrays.fill(items,-1);Arrays.fill(tags,"");}
    public void read(ByteBuffer in)throws ProtocolException {
        int mask=0;
        do {
            BinaryCodec.require(in,2);int entry=in.get()&255,slot=entry&127;
            if(slot>=6||(mask&(1<<slot))!=0)throw new ProtocolException("Invalid equipment slot");mask|=1<<slot;
            int id=-1;String tag="";
            if(in.get()!=0){id=VarInts.read(in);BinaryCodec.require(in,1);in.get();int start=in.position();NbtSkipper.skipRoot(in);int length=in.position()-start;
                if(length>1&&length<=4096){StringBuilder hex=new StringBuilder(length*2);String digits="0123456789abcdef";for(int n=0;n<length;n++){int b=in.get(start+n)&255;hex.append(digits.charAt(b>>>4)).append(digits.charAt(b&15));}tag=hex.toString();}}
            items[slot]=id;tags[slot]=tag;
            if((entry&128)==0){if(in.hasRemaining())throw new ProtocolException("Trailing equipment bytes");return;}
        }while(mask!=63);
        throw new ProtocolException("Too many equipment slots");
    }
}
