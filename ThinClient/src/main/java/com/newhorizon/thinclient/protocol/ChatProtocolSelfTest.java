package com.newhorizon.thinclient.protocol;

import java.nio.ByteBuffer;
import java.util.UUID;

public final class ChatProtocolSelfTest {
    public static void run()throws Exception{
        ChatProtocol chat=new ChatProtocol();ByteBuffer out=ByteBuffer.allocate(8192);
        chat.write(out,"  Ol\u00e1   mundo  ",123456789L,987L);out.flip();
        check(VarInts.read(out)==5,"chat packet id");check("Ol\u00e1 mundo".equals(BinaryCodec.readString(out,768)),"UTF-8 and whitespace");
        check(out.getLong()==123456789L&&out.getLong()==987L,"timestamp and salt");
        check(out.get()==0&&VarInts.read(out)==0&&mask(out)==0&&!out.hasRemaining(),"unsigned empty ack");
        ChatProtocol.Message first=ChatProtocol.read(player(1,"ovo",0,false),true);chat.accept(first);chat.accept(first);
        check(first.json.contains("chat.type.text")&&first.json.contains("Alex")&&first.json.contains("ovo"),"decorated player message");
        out.clear();chat.write(out,"/say teste",1,2);out.flip();
        check(VarInts.read(out)==4&&"say teste".equals(BinaryCodec.readString(out,768)),"command strips slash");
        out.getLong();out.getLong();check(out.get()==0&&VarInts.read(out)==1&&mask(out)==(1<<19),"duplicate signature and bit order");
        for(int i=2;i<=21;i++)chat.accept(ChatProtocol.read(player(i,"oi",i==21?1:0,false),true));
        out.clear();chat.write(out,"oi",1,2);out.flip();VarInts.read(out);BinaryCodec.readString(out,768);out.getLong();out.getLong();out.get();
        check(VarInts.read(out)==20&&mask(out)==0x7ffff,"hidden message leaves a hole in sliding window");
        ChatProtocol.Message filtered=ChatProtocol.read(player(30,"abcd",2,true),true);
        check(filtered.json.contains("a#cd")&&!filtered.json.contains("UNFILTERED"),"partial filtering overrides unsigned component");
        check(!ChatProtocol.read(player(31,"hidden",1,false),true).displayed,"fully filtered message hidden");
        for(int i=40;i<105;i++)chat.accept(ChatProtocol.read(player(i,"oi",0,false),true));
        check(chat.needsAck(),"ack threshold");out.clear();chat.writeAck(out);out.flip();
        check(VarInts.read(out)==3&&VarInts.read(out)==65&&!chat.needsAck(),"idle ack resets offset");
        ByteBuffer disguised=ByteBuffer.allocate(512);BinaryCodec.writeString(disguised,"{\"text\":\"teste\",\"color\":\"gold\"}",512);
        VarInts.write(disguised,1);BinaryCodec.writeString(disguised,"\"Servidor\"",512);disguised.put((byte)0);disguised.flip();
        check(ChatProtocol.read(disguised,false).json.contains("chat.type.announcement"),"disguised chat");
        ByteBuffer valid=player(1,"oi",0,false);int length=valid.remaining();
        for(int n=0;n<length;n++){
            ByteBuffer truncated=valid.duplicate();truncated.limit(n);
            try{ChatProtocol.read(truncated,true);throw new AssertionError("Accepted truncated chat at "+n);}catch(ProtocolException expected){}
        }
        StringBuilder longText=new StringBuilder();for(int i=0;i<300;i++)longText.append('\u00e9');
        check(ChatProtocol.normalize(longText.toString()).length()==256,"character bound");
        check(ChatProtocol.normalize("a\u00a7\u0000b").equals("ab"),"illegal chat characters removed");
        check(ChatProtocol.quote("a\"\\\nb").equals("\"a\\\"\\\\\\u000ab\""),"component JSON escaping");
        System.out.println("ChatProtocolSelfTest passed: framing, UTF-8, commands, filters, 20-message acknowledgments, truncation");
    }
    private static int mask(ByteBuffer b){return (b.get()&255)|((b.get()&255)<<8)|((b.get()&255)<<16);}
    private static ByteBuffer player(int signature,String content,int filter,boolean unsigned){
        ByteBuffer b=ByteBuffer.allocate(2048);BinaryCodec.writeUuid(b,new UUID(1,2));VarInts.write(b,0);b.put((byte)1);
        byte[] sig=new byte[256];sig[0]=(byte)signature;b.put(sig);BinaryCodec.writeString(b,content,768);b.putLong(1).putLong(2);
        VarInts.write(b,0);b.put((byte)(unsigned?1:0));if(unsigned)BinaryCodec.writeString(b,"\"UNFILTERED\"",512);
        VarInts.write(b,filter);if(filter==2){VarInts.write(b,1);b.putLong(2);}
        VarInts.write(b,0);BinaryCodec.writeString(b,"{\"text\":\"Alex\"}",512);b.put((byte)0);b.flip();return b;
    }
    private static void check(boolean value,String label){if(!value)throw new AssertionError(label);}
}
