package com.newhorizon.thinclient.world;

import com.newhorizon.thinclient.protocol.*;
import java.nio.ByteBuffer;
import java.util.*;

/** Bounded server data needed by multiplayer F3. No inferred server TPS or mob counts. */
public final class DebugNetworkState {
    public volatile String brand="unknown";
    public volatile int difficulty=-1,viewDistance=-1,simulationDistance=-1;
    public volatile boolean reduced;
    public volatile long receivedPackets,sentPackets;
    private Map<String,LinkedHashMap<String,int[]>> tags=new HashMap<>();
    public boolean handles(int id){return id==0x0c||id==0x4f||id==0x5c||id==0x6e;}
    public void read(int id,ByteBuffer in)throws ProtocolException{
        if(id==0x6e){readTags(in);return;}
        if(id==0x0c){BinaryCodec.require(in,2);int value=in.get()&255;in.get();PlayerListState.end(in);if(value>3)throw new ProtocolException("Difficulty");difficulty=value;return;}
        int value=PlayerListState.count(in,1024);PlayerListState.end(in);if(id==0x4f)viewDistance=value;else simulationDistance=value;
    }
    public void login(ByteBuffer in)throws ProtocolException{
        BinaryCodec.require(in,7);in.position(in.position()+7);int worlds=PlayerListState.count(in,1024);for(int i=0;i<worlds;i++)BinaryCodec.readString(in,32768);
        NbtSkipper.skipRoot(in);BinaryCodec.readString(in,32768);BinaryCodec.readString(in,32768);BinaryCodec.require(in,8);in.getLong();VarInts.read(in);
        int view=VarInts.read(in),simulation=VarInts.read(in);boolean limited=PlayerListState.bool(in);viewDistance=view;simulationDistance=simulation;reduced=limited;
    }
    private synchronized void readTags(ByteBuffer in)throws ProtocolException{
        Map<String,LinkedHashMap<String,int[]>> next=new HashMap<>();int registries=PlayerListState.count(in,256),retained=0,budget=262144;
        for(int r=0;r<registries;r++){
            String registry=BinaryCodec.readString(in,512);boolean wanted=registry.equals("minecraft:block")||registry.equals("minecraft:fluid")||registry.equals("minecraft:entity_type");
            int n=PlayerListState.count(in,8192);LinkedHashMap<String,int[]> values=new LinkedHashMap<>();
            for(int t=0;t<n;t++){String name=BinaryCodec.readString(in,512);int count=PlayerListState.count(in,65536);boolean keep=wanted&&retained<2048&&count<=budget;int[] ids=keep?new int[count]:null;
                for(int i=0;i<count;i++){int value=VarInts.read(in);if(value<0)throw new ProtocolException("Tag ID");if(keep)ids[i]=value;}
                if(keep){Arrays.sort(ids);values.put(name,ids);retained++;budget-=count;}}
            if(wanted)next.put(registry,values);
        }
        PlayerListState.end(in);tags=next;
    }
    public synchronized String tags(String registry,int id){StringBuilder out=new StringBuilder();Map<String,int[]> map=tags.get(registry);if(map!=null)for(Map.Entry<String,int[]> e:map.entrySet())if(Arrays.binarySearch(e.getValue(),id)>=0)out.append('\n').append('#').append(e.getKey());return out.toString();}
}
