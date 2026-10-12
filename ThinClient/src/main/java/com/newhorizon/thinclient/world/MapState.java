package com.newhorizon.thinclient.world;

import com.newhorizon.thinclient.protocol.*;
import com.newhorizon.thinclient.memory.*;
import java.nio.ByteBuffer;
import java.util.*;

/** Protocol-763 map patches, retained as palette indices (16 KiB per map). */
public final class MapState implements AutoCloseable {
    private final MemoryBudget budget;
    private final int capacity;
    private final LinkedHashMap<Integer,Entry> maps=new LinkedHashMap<>(16,.75f,true);
    private long revision;
    private int logged;
    private static final int[] SHADE={180,220,255,135};
    private static final int[] COLORS=MapPalette.BASE;
    private static final class Entry {
        final byte[] pixels=new byte[128*128];final MemoryBudget.Lease lease;long revision;
        Entry(MemoryBudget.Lease lease){this.lease=lease;}
    }
    public MapState(MemoryBudget budget,int capacity){if(capacity<1)throw new IllegalArgumentException();this.budget=budget;this.capacity=capacity;}
    public synchronized void read(ByteBuffer in)throws ProtocolException {
        int id=VarInts.read(in);BinaryCodec.require(in,3);int scale=in.get()&255;
        if(scale>4)throw new ProtocolException("Invalid map scale");
        in.get();boolean icons=in.get()!=0;
        if(icons){int count=VarInts.read(in);if(count<0||count>1024)throw new ProtocolException("Too many map icons");
            for(int i=0;i<count;i++){VarInts.read(in);BinaryCodec.require(in,4);in.get();in.get();in.get();if(in.get()!=0)BinaryCodec.readString(in,262144);}}
        BinaryCodec.require(in,1);int width=in.get()&255;
        if(width==0){if(in.hasRemaining())throw new ProtocolException("Trailing map bytes");return;}
        BinaryCodec.require(in,3);int height=in.get()&255,x=in.get()&255,y=in.get()&255;
        int length=VarInts.read(in);
        if(width>128||height<1||height>128||x+width>128||y+height>128||length!=width*height||in.remaining()!=length)
            throw new ProtocolException("Invalid map patch rectangle");
        Entry e=maps.get(id);
        if(e==null){
            if(maps.size()>=capacity){Iterator<Entry> it=maps.values().iterator();Entry old=it.next();it.remove();old.lease.close();}
            MemoryBudget.Lease lease=budget.tryReserve(MemoryCategory.DISPLAY_STATE,128*128+128);
            if(lease==null){in.position(in.limit());return;}
            e=new Entry(lease);maps.put(id,e);
        }
        for(int row=0;row<height;row++)in.get(e.pixels,(y+row)*128+x,width);
        e.revision=++revision;
        if(logged++<24)System.out.println("[NH-MAP] received id="+id+" patch="+width+"x"+height+" at="+x+","+y+" cached="+maps.size());
    }
    /** Caller reuses one 64 KiB staging buffer; no expanded CPU texture cache. */
    public synchronized long copyRgba(int id,long known,ByteBuffer out){
        Entry e=maps.get(id);if(e==null)return -1;if(e.revision==known)return known;
        out.clear();for(byte pixel:e.pixels){int c=color(pixel&255);out.put((byte)(c>>16)).put((byte)(c>>8)).put((byte)c).put((byte)(c>>>24));}out.flip();return e.revision;
    }
    /** Palette indices stay compact all the way to the GPU; unchanged maps are not uploaded. */
    public synchronized long copyIndices(int id,long known,ByteBuffer out){
        Entry e=maps.get(id);if(e==null)return -1;if(e.revision==known)return known;
        out.clear();out.put(e.pixels);out.flip();return e.revision;
    }
    public static int color(int index){int base=index>>>2;if(base==0||base>=COLORS.length)return 0;int c=COLORS[base],shade=SHADE[index&3];return 0xff000000|(((c>>16&255)*shade/255)<<16)|(((c>>8&255)*shade/255)<<8)|((c&255)*shade/255);}
    public synchronized int size(){return maps.size();}
    public synchronized void close(){for(Entry e:maps.values())e.lease.close();maps.clear();}
}
