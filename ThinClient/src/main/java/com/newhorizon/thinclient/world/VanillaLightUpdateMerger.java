package com.newhorizon.thinclient.world;

import com.newhorizon.thinclient.protocol.BinaryCodec;
import com.newhorizon.thinclient.protocol.ProtocolException;
import com.newhorizon.thinclient.protocol.VarInts;
import java.nio.ByteBuffer;
import java.util.Arrays;

/** Merges partial authoritative light packets; missing sections keep their previous value. */
final class VanillaLightUpdateMerger {
    private static final int SECTIONS=SurfaceChunk.MAX_LIGHT_SECTIONS;
    private static final int NEW_SOURCE=1<<30;
    private final int[] skyOffsets=new int[SECTIONS],blockOffsets=new int[SECTIONS];

    void merge(ByteBuffer previous,ByteBuffer update,ByteBuffer output)throws ProtocolException {
        Arrays.fill(skyOffsets,-1);Arrays.fill(blockOffsets,-1);
        if(previous!=null)read(previous.duplicate(),false);
        read(update.duplicate(),true);
        output.clear();
        mask(output,skyOffsets,false);mask(output,blockOffsets,false);
        mask(output,skyOffsets,true);mask(output,blockOffsets,true);
        arrays(output,skyOffsets,previous,update);arrays(output,blockOffsets,previous,update);
        output.flip();
    }
    private void read(ByteBuffer input,boolean latest)throws ProtocolException {
        long sky=mask(input),block=mask(input),emptySky=mask(input),emptyBlock=mask(input);
        for(int s=0;s<SECTIONS;s++) {
            if((emptySky&(1L<<s))!=0)skyOffsets[s]=-2;
            if((emptyBlock&(1L<<s))!=0)blockOffsets[s]=-2;
        }
        readArrays(input,sky,skyOffsets,latest);readArrays(input,block,blockOffsets,latest);
        if(input.hasRemaining())throw new ProtocolException("Trailing cached light bytes "+input.remaining());
    }
    private static long mask(ByteBuffer input)throws ProtocolException {
        int count=VarInts.read(input);
        if(count<0||count>1)throw new ProtocolException("Light mask outside supported height "+count);
        BinaryCodec.require(input,count*8);return count==0?0:input.getLong();
    }
    private static void readArrays(ByteBuffer input,long mask,int[] offsets,boolean latest)throws ProtocolException {
        int count=VarInts.read(input);
        if(count!=Long.bitCount(mask))throw new ProtocolException("Light array count does not match mask "+count);
        for(int s=0;s<64;s++)if((mask&(1L<<s))!=0) {
            int length=VarInts.read(input);
            if(length!=SurfaceChunk.LIGHT_SECTION_BYTES)throw new ProtocolException("Invalid light array length "+length);
            BinaryCodec.require(input,length);
            if(s<SECTIONS)offsets[s]=input.position()|(latest?NEW_SOURCE:0);
            input.position(input.position()+length);
        }
    }
    private static void mask(ByteBuffer output,int[] offsets,boolean empty) {
        long bits=0;
        for(int s=0;s<SECTIONS;s++)if(empty?offsets[s]==-2:offsets[s]>=0)bits|=1L<<s;
        VarInts.write(output,bits==0?0:1);if(bits!=0)output.putLong(bits);
    }
    private static void arrays(ByteBuffer output,int[] offsets,ByteBuffer previous,ByteBuffer update) {
        int count=0;for(int offset:offsets)if(offset>=0)count++;
        VarInts.write(output,count);
        for(int offset:offsets)if(offset>=0) {
            VarInts.write(output,SurfaceChunk.LIGHT_SECTION_BYTES);
            ByteBuffer source=(offset&NEW_SOURCE)!=0?update:previous;
            int begin=offset&~NEW_SOURCE;
            for(int i=0;i<SurfaceChunk.LIGHT_SECTION_BYTES;i++)output.put(source.get(begin+i));
        }
    }
}