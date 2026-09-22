package com.newhorizon.thinclient.world;

import com.newhorizon.thinclient.protocol.*;
import java.nio.ByteBuffer;
import java.util.*;

/** Protocol 763 player information, also used by skins. Unlisted NPC profiles remain available. */
public final class PlayerListState {
    public static final int UPDATE=0x3a, REMOVE=0x39, HEADER=0x65, CAPACITY=256;
    private final LinkedHashMap<UUID,Entry> entries=new LinkedHashMap<>();
    private UUID localId;
    private String localName="Player",header="\"\"",footer="\"\"";
    private long revision;
    public static final class Entry {
        public final UUID id;
        public String name="",textures="",display="";
        public boolean listed;
        public int latency,gameMode;
        Entry(UUID id){this.id=id;}
        Entry(Entry old){id=old.id;name=old.name;textures=old.textures;display=old.display;listed=old.listed;latency=old.latency;gameMode=old.gameMode;}
    }
    public synchronized void identity(UUID id,String name){localId=id;localName=name;revision++;}
    public synchronized UUID localId(){return localId;}
    public synchronized String localName(){return localName;}
    public synchronized long revision(){return revision;}
    public synchronized Entry get(UUID id){Entry e=entries.get(id);return e==null?null:new Entry(e);}
    public synchronized String textures(UUID id){Entry e=entries.get(id);return e==null?"":e.textures;}
    public synchronized List<Entry> listed(){
        List<Entry> out=new ArrayList<>();for(Entry e:entries.values())if(e.listed)out.add(new Entry(e));return out;
    }
    public synchronized String header(){return header;}
    public synchronized String footer(){return footer;}
    public synchronized void clear(){entries.clear();header=footer="\"\"";revision++;}
    public synchronized void read(int packetId,ByteBuffer in)throws ProtocolException{
        if(packetId==HEADER){String h=BinaryCodec.readString(in,32768),f=BinaryCodec.readString(in,32768);end(in);header=h;footer=f;revision++;return;}
        if(packetId==REMOVE){int n=count(in,4096);List<UUID> ids=new ArrayList<>();for(int i=0;i<n;i++)ids.add(BinaryCodec.readUuid(in));end(in);for(UUID id:ids){Entry e=entries.get(id);if(e!=null)e.listed=false;}revision++;return;}
        BinaryCodec.require(in,1);int mask=in.get()&255;if((mask&~63)!=0)throw new ProtocolException("Player info actions");
        int n=count(in,4096);LinkedHashMap<UUID,Entry> pending=new LinkedHashMap<>();
        for(int i=0;i<n;i++){
            UUID id=BinaryCodec.readUuid(in);Entry old=pending.containsKey(id)?pending.get(id):entries.get(id),e=old==null?new Entry(id):new Entry(old);
            if((mask&1)!=0){
                e.name=BinaryCodec.readString(in,64);if(e.name.length()>16)throw new ProtocolException("Player name");e.textures="";
                int properties=count(in,64);for(int p=0;p<properties;p++){
                    String key=BinaryCodec.readString(in,256),value=BinaryCodec.readString(in,32768);
                    if(bool(in))BinaryCodec.readString(in,16384);
                    if("textures".equals(key))e.textures=value;
                }
            }
            if((mask&2)!=0&&bool(in)){BinaryCodec.readUuid(in);BinaryCodec.require(in,8);in.getLong();BinaryCodec.readByteArray(in,8192);BinaryCodec.readByteArray(in,8192);}
            if((mask&4)!=0)e.gameMode=VarInts.read(in);
            if((mask&8)!=0)e.listed=bool(in);
            if((mask&16)!=0)e.latency=VarInts.read(in);
            if((mask&32)!=0)e.display=bool(in)?BinaryCodec.readString(in,32768):"";
            // Parse the entire packet, but never retain an unbounded number of profiles.
            if(old!=null||(mask&1)!=0) {if(pending.size()<CAPACITY||pending.containsKey(id))pending.put(id,e);}
        }
        end(in);
        for(Entry e:pending.values()){
            if(!entries.containsKey(e.id)&&entries.size()>=CAPACITY){UUID victim=null;for(Entry v:entries.values())if(!v.id.equals(localId)&&!v.listed){victim=v.id;break;}if(victim==null)continue;entries.remove(victim);}
            entries.put(e.id,e);
        }
        revision++;
    }
    static boolean bool(ByteBuffer in)throws ProtocolException{BinaryCodec.require(in,1);return in.get()!=0;}
    static int count(ByteBuffer in,int max)throws ProtocolException{int n=VarInts.read(in);if(n<0||n>max)throw new ProtocolException("HUD collection limit");return n;}
    static void end(ByteBuffer in)throws ProtocolException{if(in.hasRemaining())throw new ProtocolException("Trailing HUD bytes");}
}
