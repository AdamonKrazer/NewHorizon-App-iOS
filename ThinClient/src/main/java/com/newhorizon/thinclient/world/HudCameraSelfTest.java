package com.newhorizon.thinclient.world;

import com.newhorizon.thinclient.protocol.*;
import com.newhorizon.thinclient.memory.MemoryBudget;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class HudCameraSelfTest {
    private static final UUID ID=UUID.fromString("00112233-4455-6677-8899-aabbccddeeff");
    public static void run()throws Exception{profiles();hud();camera();skins();System.out.println("HUD/camera tests passed: profile actions/truncation, NPC retention, tab, scoreboard, bosses, titles, perspective collision, skin bounds and legacy alpha");}
    private static ByteBuffer packet(){return ByteBuffer.allocate(65536);}
    private static void str(ByteBuffer b,String s){BinaryCodec.writeString(b,s,32768);}
    private static void check(boolean ok,String label){if(!ok)throw new AssertionError(label);}
    private static ByteBuffer profile(){ByteBuffer b=packet();b.put((byte)63);VarInts.write(b,1);BinaryCodec.writeUuid(b,ID);str(b,"Adamo");VarInts.write(b,1);str(b,"textures");str(b,"test-textures");b.put((byte)0);
        b.put((byte)1);BinaryCodec.writeUuid(b,ID);b.putLong(123);BinaryCodec.writeByteArray(b,new byte[]{1,2},8192);BinaryCodec.writeByteArray(b,new byte[]{3},8192);
        VarInts.write(b,1);b.put((byte)1);VarInts.write(b,42);b.put((byte)1);str(b,"{\"text\":\"Display Name\"}");return (ByteBuffer)b.flip();}
    private static void profiles()throws Exception{
        PlayerListState p=new PlayerListState();p.identity(ID,"Adamo");ByteBuffer full=profile();p.read(PlayerListState.UPDATE,full.duplicate());
        PlayerListState.Entry e=p.get(ID);check(e.gameMode==1&&e.latency==42&&e.listed&&e.textures.equals("test-textures")&&e.display.contains("Display Name"),"all six actions including optional chat session");
        for(int n=0;n<full.limit();n++){ByteBuffer cut=full.duplicate();cut.limit(n);long revision=p.revision();try{p.read(PlayerListState.UPDATE,cut);throw new AssertionError("truncated player info accepted");}catch(ProtocolException expected){}check(p.revision()==revision&&p.listed().size()==1,"atomic truncated profile");}
        ByteBuffer b=packet();b.put((byte)16);VarInts.write(b,1);BinaryCodec.writeUuid(b,ID);VarInts.write(b,222);b.flip();p.read(PlayerListState.UPDATE,b);check(p.get(ID).latency==222&&p.get(ID).name.equals("Adamo"),"partial update keeps identity");
        b=packet();VarInts.write(b,1);BinaryCodec.writeUuid(b,ID);b.flip();p.read(PlayerListState.REMOVE,b);check(p.listed().isEmpty()&&p.textures(ID).equals("test-textures"),"NPC removed from tab keeps cached skin identity");
        p.clear();check(p.get(ID)==null&&p.localId().equals(ID),"connection reset cache");
    }
    private static void hud()throws Exception{
        PlayerListState p=new PlayerListState();p.identity(ID,"Adamo");p.read(PlayerListState.UPDATE,profile());ServerHudState h=new ServerHudState();ByteBuffer b=packet();
        BinaryCodec.writeUuid(b,ID);VarInts.write(b,0);str(b,"\"Boss\"");b.putFloat(.75f);VarInts.write(b,5);VarInts.write(b,2);b.put((byte)0);b.flip();h.read(0x0b,b);
        b=packet();str(b,"points");b.put((byte)0);str(b,"\"Scoreboard\"");VarInts.write(b,0);b.flip();h.read(0x58,b);
        b=packet();b.put((byte)1);str(b,"points");b.flip();h.read(0x51,b);
        b=packet();str(b,"Adamo");VarInts.write(b,0);str(b,"points");VarInts.write(b,15);b.flip();h.read(0x5b,b);
        b=packet();str(b,"\"Welcome\"");b.flip();h.read(0x5f,b);
        String json=h.encode(p,true,System.nanoTime()+600_000_000L);
        check(json.contains("Boss")&&json.contains("0.75")&&json.contains("Scoreboard")&&json.contains("\"value\":15")&&json.contains("Display Name")&&json.contains("Welcome")&&json.contains("\"titleAlpha\":1.0"),"vanilla HUD fields");
        b=packet();BinaryCodec.writeUuid(b,ID);VarInts.write(b,1);b.flip();h.read(0x0b,b);
        b=packet();str(b,"Adamo");VarInts.write(b,1);str(b,"");b.flip();h.read(0x5b,b);
        b=packet();b.put((byte)1);b.flip();h.read(0x0e,b);json=h.encode(p,false,System.nanoTime());
        check(json.contains("\"bosses\":[]")&&json.contains("\"scores\":[]")&&json.contains("\"players\":[]")&&json.contains("\"titleAlpha\":0.0"),"remove bars, clear title and all owner scores");
    }
    private static void camera()throws Exception{
        MemoryBudget budget=MemoryBudget.lowRamDefaults();try(WorldChunkStore w=new WorldChunkStore(budget,2,64)){
            PerspectiveCamera c=new PerspectiveCamera();c.update(w,8,2,8,0,20);check(c.z==8&&c.pitch==20,"first-person preserves eye");
            c.cycle();c.update(w,8,2,8,0,0);check(c.mode==1&&Math.abs(c.z-4)<1e-6&&c.yaw==0,"back camera four blocks");
            c.cycle();c.update(w,8,2,8,0,20);check(c.mode==2&&c.z>11&&c.yaw==180&&c.pitch== -20,"front reverses presentation only");
            c.cycle();check(c.mode==0,"three-state cycle");
            w.handle(new WorldMessage.Reset("minecraft:overworld",0,16));
            ByteBuffer section=packet();section.putShort((short)0).put((byte)0);VarInts.write(section,0);VarInts.write(section,0);
            section.put((byte)0);VarInts.write(section,0);VarInts.write(section,0);section.flip();w.applyVanillaChunkData(0,0,section);
            check(w.applyVanillaBlockUpdate(8,2,6,1)&&w.applyVanillaBlockUpdate(7,2,6,1),"authoritative wall fixture");
            c.mode=1;c.update(w,8,2.5,8,0,0);check(c.z>=7&&c.z<8,"eight rays stop before stone wall");
        }
    }
    private static void skins()throws Exception{
        String json="{\"textures\":{\"SKIN\":{\"url\":\"http://textures.minecraft.net/texture/0123456789abcdef0123456789abcdef\",\"metadata\":{\"model\":\"slim\"}}}}";
        String[] t=PlayerSkins.texture(Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8)));check(t!=null&&t[0].startsWith("https://")&&t[1].equals("slim"),"texture property model and HTTPS");
        check(PlayerSkins.texture(Base64.getEncoder().encodeToString(json.replace("textures.minecraft.net","127.0.0.1").getBytes(StandardCharsets.UTF_8)))==null,"skin property cannot access local network");
        BufferedImage legacy=new BufferedImage(64,32,BufferedImage.TYPE_INT_ARGB);for(int y=0;y<32;y++)for(int x=0;x<64;x++)legacy.setRGB(x,y,0xff000000|x<<16|y);
        int[] normalized=PlayerSkins.normalize(legacy);check(normalized.length==4096&&(normalized[40]>>>24)==0&&(normalized[0]>>>24)==255,"legacy opaque hat removed while base is opaque");
        check((normalized[52*64+23]&0xffffff)==(legacy.getRGB(4,20)&0xffffff),"legacy left limb mirrored from right");
        try(PlayerSkins cache=new PlayerSkins()){PlayerSkins.Slot a=cache.request(ID,"");check(a.skin.pixels.length==4096&&a.skin.encoded().length()==21848,"default atlas and bounded bridge encoding");}
        try(PlayerSkins cache=new PlayerSkins()){for(int i=0;i<18;i++){PlayerSkins.Slot s=cache.request(new UUID(0,i),"");check(s.skin.pixels.length==4096&&s.skin.slim==(i<9),"all eighteen vanilla default skins");}}
    }
}
