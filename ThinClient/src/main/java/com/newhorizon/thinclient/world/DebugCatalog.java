package com.newhorizon.thinclient.world;

import java.io.*;
/** Official registry names and state properties, shared by F3 target descriptions. */
public final class DebugCatalog {
    private final String[] blocks,properties,entities,fluids;
    private final short[] block,property;
    private static class Holder {static final DebugCatalog VALUE=new DebugCatalog();}
    public static DebugCatalog get(){return Holder.VALUE;}
    private DebugCatalog(){
        try(DataInputStream in=new DataInputStream(DebugCatalog.class.getResourceAsStream("/assets/newhorizon/debug_1_20_1.bin"))){
            if(in.readInt()!=0x4e484442||in.readInt()!=763)throw new IOException("Debug catalog version");
            blocks=strings(in,2048);properties=strings(in,32768);entities=strings(in,256);fluids=strings(in,32);
            int n=in.readInt();if(n!=24135)throw new IOException("Debug states");block=new short[n];property=new short[n];
            for(int i=0;i<n;i++){block[i]=in.readShort();property[i]=in.readShort();if((block[i]&65535)>=blocks.length||(property[i]&65535)>=properties.length)throw new IOException("Debug index");}
        }catch(IOException e){throw new IllegalStateException("Vanilla debug catalog",e);}
    }
    private static String[] strings(DataInputStream in,int limit)throws IOException{int n=in.readInt();if(n<0||n>limit)throw new IOException("Debug strings");String[] out=new String[n];for(int i=0;i<n;i++)out[i]=in.readUTF();return out;}
    public int blockId(int state){return state>=0&&state<block.length?block[state]&65535:-1;}
    public String block(int state){int id=blockId(state);return id<0?"unknown":blocks[id];}
    public String properties(int state){return state>=0&&state<property.length?properties[property[state]&65535]:"";}
    public String entity(int type){return type>=0&&type<entities.length?entities[type]:"unknown";}
    public int fluidId(String name){for(int i=0;i<fluids.length;i++)if(fluids[i].equals(name))return i;return -1;}
}
