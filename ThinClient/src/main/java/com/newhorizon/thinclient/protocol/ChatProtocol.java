package com.newhorizon.thinclient.protocol;

import java.nio.ByteBuffer;
import java.util.Arrays;

/** 1.20.1 chat framing and the vanilla twenty-message acknowledgment window. */
public final class ChatProtocol {
    public static final int PLAYER=0x35, DISGUISED=0x1b;
    private final boolean[] seen=new boolean[20];
    private int next, offset;
    private byte[] lastSignature;

    public static final class Message {
        public final String json;
        final byte[] signature;
        final boolean displayed;
        Message(String json,byte[] signature,boolean displayed){this.json=json;this.signature=signature;this.displayed=displayed;}
    }

    public static Message read(ByteBuffer in,boolean player) throws ProtocolException {
        byte[] signature=null;
        String content;
        boolean displayed=true;
        if(player){
            BinaryCodec.readUuid(in);
            if(VarInts.read(in)<0)throw new ProtocolException("Negative chat index");
            if(bool(in)){BinaryCodec.require(in,256);signature=new byte[256];in.get(signature);}
            String plain=BinaryCodec.readString(in,768);
            if(plain.length()>256)throw new ProtocolException("Chat body too long");
            BinaryCodec.require(in,16);in.getLong();in.getLong(); // timestamp and salt
            int count=VarInts.read(in);
            if(count<0||count>20)throw new ProtocolException("Invalid last-seen list");
            for(int i=0;i<count;i++){
                int packed=VarInts.read(in);
                if(packed<0||packed>128)throw new ProtocolException("Invalid signature reference");
                if(packed==0){BinaryCodec.require(in,256);in.position(in.position()+256);}
            }
            String unsigned=bool(in)?BinaryCodec.readString(in,262144):null;
            int filter=VarInts.read(in);
            if(filter<0||filter>2)throw new ProtocolException("Invalid chat filter");
            if(filter==1)displayed=false;
            if(filter==2){
                int words=VarInts.read(in);
                if(words<0||words>4)throw new ProtocolException("Invalid chat filter mask");
                BinaryCodec.require(in,words*8);
                char[] chars=plain.toCharArray();
                for(int w=0;w<words;w++){
                    long bits=in.getLong();
                    for(int b=0;b<64&&w*64+b<chars.length;b++)if((bits&(1L<<b))!=0)chars[w*64+b]='#';
                }
                plain=new String(chars);unsigned=null;
            }
            content=unsigned!=null?unsigned:quote(plain);
        }else content=BinaryCodec.readString(in,262144);
        int type=VarInts.read(in);
        String name=BinaryCodec.readString(in,262144);
        String target=bool(in)?BinaryCodec.readString(in,262144):"\"\"";
        if(in.hasRemaining())throw new ProtocolException("Trailing chat bytes");
        String key;
        String args=name+","+content;
        switch(type){
            case 1:key="chat.type.announcement";break;
            case 2:key="commands.message.display.incoming";break;
            case 3:key="commands.message.display.outgoing";args=target+","+content;break;
            case 4:key="chat.type.team.text";args=target+","+name+","+content;break;
            case 5:key="chat.type.team.sent";args=target+","+name+","+content;break;
            case 6:key="chat.type.emote";break;
            default:key="chat.type.text";break;
        }
        return new Message("{\"translate\":"+quote(key)+",\"with\":["+args+"]}",signature,displayed);
    }

    public void accept(Message message){
        if(message.signature==null||Arrays.equals(lastSignature,message.signature))return;
        lastSignature=message.signature;
        seen[next]=message.displayed;next=(next+1)%20;offset++;
    }
    public boolean needsAck(){return offset>64;}
    public void writeAck(ByteBuffer out){VarInts.write(out,0x03);VarInts.write(out,offset);offset=0;}
    public void write(ByteBuffer out,String input,long timestamp,long salt){
        String message=normalize(input);
        if(message.isEmpty()||message.equals("/"))throw new IllegalArgumentException("Empty chat");
        boolean command=message.charAt(0)=='/';
        VarInts.write(out,command?0x04:0x05);
        BinaryCodec.writeString(out,command?message.substring(1):message,768);
        out.putLong(timestamp);out.putLong(salt);
        out.put((byte)0); // empty command signatures or absent message signature
        VarInts.write(out,offset);offset=0;
        int mask=0;
        for(int i=0;i<20;i++)if(seen[(next+i)%20])mask|=1<<i;
        out.put((byte)mask).put((byte)(mask>>>8)).put((byte)(mask>>>16));
    }
    public static String normalize(String input){
        if(input==null)return "";
        StringBuilder out=new StringBuilder();boolean space=false;
        for(int i=0;i<input.length()&&out.length()<256;i++){
            char c=input.charAt(i);
            if(Character.isWhitespace(c)){space=out.length()>0;continue;}
            if(c<32||c==127||c=='\u00a7')continue;
            if(space&&out.length()<255)out.append(' ');space=false;
            out.append(c);
        }
        if(out.length()>0&&Character.isHighSurrogate(out.charAt(out.length()-1)))out.setLength(out.length()-1);
        return out.toString();
    }
    private static boolean bool(ByteBuffer in)throws ProtocolException{BinaryCodec.require(in,1);return in.get()!=0;}
    public static String quote(String value){
        StringBuilder out=new StringBuilder("\"");
        for(int i=0;i<value.length();i++){
            char c=value.charAt(i);
            if(c=='"'||c=='\\')out.append('\\');
            if(c<32){out.append(String.format(java.util.Locale.ROOT,"\\u%04x",(int)c));}else out.append(c);
        }
        return out.append('"').toString();
    }
}
