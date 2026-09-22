package com.newhorizon.thinclient.display;

import com.newhorizon.thinclient.protocol.*;
import java.nio.ByteBuffer;
import java.io.*;
import java.util.*;

/** The original mod's item data, read independently of Forge and without retaining arbitrary NBT. */
public final class MinePadData {
    public static final String HOME="mod://webdisplays/main.html";
    public static final int MAX_TABS=64,MAX_URL=8192;
    public final UUID id;
    public final String[] urls;
    public final int index;
    public MinePadData(UUID id,String[] urls,int index){
        this.id=id;this.urls=urls.length==0?new String[]{HOME}:urls.clone();
        this.index=Math.max(0,Math.min(this.urls.length-1,index));
    }
    public static MinePadData read(ByteBuffer source)throws ProtocolException{
        ByteBuffer in=source.duplicate();BinaryCodec.require(in,1);int root=in.get()&255;
        if(root==0)return null;if(root!=10)throw new ProtocolException("MinePad NBT root");
        string(in);UUID id=null;String url=HOME;ArrayList<String> tabs=new ArrayList<>();int index=0;boolean found=false;
        while(true){
            BinaryCodec.require(in,1);int type=in.get()&255;if(type==0)break;
            String key=string(in);
            if(type==11&&key.equals("PadID")){
                BinaryCodec.require(in,20);if(in.getInt()!=4)throw new ProtocolException("MinePad UUID length");
                id=new UUID(((long)in.getInt()<<32)|(in.getInt()&0xffffffffL),((long)in.getInt()<<32)|(in.getInt()&0xffffffffL));found=true;
            }else if(type==8&&key.equals("PadURL")){url=string(in);found=true;}
            else if(type==3&&key.equals("PadTabIdx")){BinaryCodec.require(in,4);index=in.getInt();}
            else if(type==9&&key.equals("PadTabs")){
                BinaryCodec.require(in,5);int item=in.get()&255,count=in.getInt();
                if(count<0||count>MAX_TABS||item!=8)throw new ProtocolException("MinePad tab list");
                for(int i=0;i<count;i++)tabs.add(string(in));found=true;
            }else NbtSkipper.skipUnnamed(in,type);
        }
        if(!found)return null;
        if(tabs.isEmpty())tabs.add(url.isEmpty()?HOME:url);
        return new MinePadData(id,tabs.toArray(new String[0]),index);
    }
    private static String string(ByteBuffer in)throws ProtocolException{
        BinaryCodec.require(in,2);int size=in.getShort()&65535;BinaryCodec.require(in,size);
        if(size>MAX_URL)throw new ProtocolException("MinePad NBT string too long");
        byte[] encoded=new byte[size+2];encoded[0]=(byte)(size>>8);encoded[1]=(byte)size;in.get(encoded,2,size);
        try{return new DataInputStream(new ByteArrayInputStream(encoded)).readUTF();}
        catch(IOException e){throw new ProtocolException("MinePad NBT string",e);}
    }
    public static ByteBuffer request(UUID id,int action,int index,String url){
        if(id==null||action<0||action>4||url==null||url.length()>MAX_URL)throw new IllegalArgumentException("MinePad request");
        ByteBuffer out=ByteBuffer.allocate(40+url.length()*3);
        VarInts.write(out,15);BinaryCodec.writeUuid(out,id);out.put((byte)action);VarInts.write(out,index);
        BinaryCodec.writeString(out,url,MAX_URL*3);out.flip();return out;
    }
    public static String normalize(String input){
        String url=input==null?"":input.trim();
        if(url.isEmpty())return HOME;
        if(url.length()>MAX_URL||url.indexOf('\n')>=0||url.indexOf('\r')>=0)throw new IllegalArgumentException("Invalid tablet URL");
        if(url.indexOf(':')<0)url="https://"+url;
        String scheme=url.substring(0,url.indexOf(':')).toLowerCase(Locale.ROOT);
        if(!scheme.equals("http")&&!scheme.equals("https")&&!scheme.equals("mod")&&!scheme.equals("webdisplays")&&!url.equals("about:blank"))throw new IllegalArgumentException("Unsupported tablet URL");
        return url;
    }
    public static boolean item(String value){return value!=null&&(value.toLowerCase(Locale.ROOT).contains("minepad")||value.toLowerCase(Locale.ROOT).contains("tablet"));}
    public boolean same(MinePadData other){return other!=null&&Objects.equals(id,other.id)&&index==other.index&&Arrays.equals(urls,other.urls);}
}
