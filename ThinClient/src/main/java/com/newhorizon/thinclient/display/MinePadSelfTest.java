package com.newhorizon.thinclient.display;

import com.newhorizon.thinclient.inventory.InventoryState;
import com.newhorizon.thinclient.memory.MemoryBudget;
import com.newhorizon.thinclient.protocol.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.util.*;

public final class MinePadSelfTest {
    public static void run()throws Exception{
        UUID id=new UUID(0x12345678fedcba98L,0xaabbccdd11223344L);
        byte[] encoded=tag(id,new String[]{MinePadData.HOME,"https://example.org/a?x=1&y=2"},1);
        MinePadData data=MinePadData.read(ByteBuffer.wrap(encoded));
        check(id.equals(data.id)&&data.index==1&&data.urls.length==2,"original item NBT");
        for(int i=0;i<encoded.length;i++){
            try{MinePadData.read(ByteBuffer.wrap(encoded,0,i));throw new AssertionError("truncated NBT accepted");}catch(ProtocolException expected){}
        }
        for(int action=0;action<=4;action++){
            ByteBuffer packet=MinePadData.request(id,action,-1,"https://example.org/");
            check(VarInts.read(packet)==15&&BinaryCodec.readUuid(packet).equals(id)&&packet.get()==action,"mod discriminator/UUID/action");
            check(VarInts.read(packet)==-1&&BinaryCodec.readString(packet,8192).equals("https://example.org/")&&!packet.hasRemaining(),"original VarInt index and URL framing");
        }
        check(MinePadData.normalize(" example.org ").equals("https://example.org"),"bare URL");
        try{MinePadData.normalize("javascript:alert(1)");throw new AssertionError("URL scheme");}catch(IllegalArgumentException expected){}

        Fake port=new Fake();DisplayController displays=new DisplayController(MemoryBudget.lowRamDefaults(),port,8,32,48);
        int worldSlot=displays.reserveAuxiliaryBrowser();
        ArrayList<String> commands=new ArrayList<>();UUID[] initialized={null};
        MinePadController pads=new MinePadController(displays,port,(uuid,action,index,url)->{initialized[0]=uuid;commands.add(action+":"+index+":"+url);});
        InventoryState inv=new InventoryState();inv.setCreativeSlot(36,"webdisplays:minepad",1,1200,"");
        pads.update(inv,1_000_000_000L);pads.open(inv,0,false,1_000_000_000L);
        check(pads.isOpen()&&commands.get(0).startsWith("0:0:mod://"),"uninitialized item powers on via original custom packet");
        int first=port.shown;check(first!=worldSlot&&first>0,"native browser ids isolated from world displays");
        writeSlot(inv,initialized[0],new String[]{MinePadData.HOME},0);
        pads.update(inv,1_100_000_000L);check(pads.heldBrowser(false)==first,"ack preserves page instance");
        pads.hide();pads.update(inv,1_200_000_000L);check(!pads.isOpen()&&port.live.contains(first),"closing retains session for hand display");
        pads.open(inv,0,false,1_300_000_000L);check(port.shown==first&&port.creates==1,"reopen uses same Gecko session");
        pads.action(1,0,"example.org");int second=port.shown;check(second!=first&&commands.get(commands.size()-1).startsWith("1:"),"new tab");
        pads.action(2,0,"");check(port.shown==first,"switch restores original page");
        pads.location(first,"https://example.org/final");pads.update(inv,2_200_000_000L);
        check(commands.get(commands.size()-1).equals("0:0:https://example.org/final"),"redirect is persisted to the original item");
        int requests=commands.size();pads.update(inv,2_300_000_000L);check(commands.size()==requests,"no navigation echo loop");
        pads.action(3,1,"");check(!port.live.contains(second)&&port.live.contains(first),"closing one tab retains the other");
        pads.action(2,-1,"");check(port.shown==first,"tab index wraps");
        for(int i=0;i<12;i++)pads.action(1,0,"https://example.org/"+i);
        check(port.live.size()<=MinePadController.MAX_LIVE,"bounded live browser count");
        pads.action(4,0,"");check(!pads.isOpen()&&port.live.isEmpty()&&commands.get(commands.size()-1).startsWith("4:"),"shutdown closes sessions and sends shutdown action");
        writeSlot(inv,initialized[0],new String[]{MinePadData.HOME},0);pads.update(inv,2_400_000_000L);
        check(port.live.isEmpty(),"stale NBT cannot resurrect shutdown tablet");
        UUID stopped=initialized[0];pads.open(inv,0,false,2_500_000_000L);
        check(pads.isOpen()&&!stopped.equals(initialized[0]),"immediate power-on allocates a new identity");
        writeSlot(inv,stopped,new String[]{MinePadData.HOME},0);pads.update(inv,2_600_000_000L);
        check(pads.isOpen()&&!port.live.isEmpty(),"shutdown acknowledgement cannot discard a pending new power-on");
        writeSlot(inv,initialized[0],new String[]{MinePadData.HOME},0);pads.update(inv,2_700_000_000L);
        inv.setCreativeSlot(36,"webdisplays:minepad",1,1200,"");
        check(inv.pad(0)==null,"creative replacement cannot inherit a previous tablet identity");
        pads.close();displays.releaseAuxiliaryBrowser(worldSlot);displays.close();check(port.errors==0,"controller has no errors");
        displayLinks();
        System.out.println("MinePadSelfTest passed: original NBT and packets, initialize/ack, retained pages, tabs, redirects, bounded sessions, shutdown, shared IDs");
    }
    private static void displayLinks(){
        check(!DisplayInteractionPolicy.allowed("AIR","AIR",false,false),"bare hands cannot click displays");
        check(!DisplayInteractionPolicy.allowed("DIAMOND_SWORD","",false,false),"ordinary items cannot click displays");
        check(!DisplayInteractionPolicy.laser("other:laserpointer")&&!DisplayInteractionPolicy.laser("fake_laserpointer_item"),"exact laser identity");
        check(DisplayInteractionPolicy.allowed("webdisplays:laserpointer","",false,false),"main-hand laser");
        check(DisplayInteractionPolicy.allowed("BREAD","WEBDISPLAYS_LASERPOINTER",false,false),"offhand laser");
        check(!DisplayInteractionPolicy.allowed("laserpointer","",true,false)&&!DisplayInteractionPolicy.allowed("laserpointer","",false,true),"screen/death revoke input");
        Fake port=new Fake();ArrayList<String> commands=new ArrayList<>();
        DisplayController displays=new DisplayController(MemoryBudget.lowRamDefaults(),port,8,32,48);
        MinePadController pads=new MinePadController(displays,port,(id,action,index,url)->commands.add(action+":"+url));
        InventoryState inv=new InventoryState();inv.setCreativeSlot(36,"webdisplays:laserpointer",1,1201,"");
        inv.setCreativeSlot(39,"webdisplays:minepad",1,1200,"");pads.update(inv,1_000_000_000L);
        check(pads.receiveDisplayUrl(inv,"https://example.org/link",1_100_000_000L),"find tablet elsewhere in inventory");
        check(!pads.isOpen()&&port.shown==-1,"link updates tablet without stealing game controls");
        check(commands.size()==2&&commands.get(0).startsWith("0:")&&commands.get(1).equals("1:https://example.org/link"),"initialize then add original server tab");
        pads.open(inv,3,false,1_200_000_000L);check(pads.isOpen()&&port.shown>0,"new tab can be opened later");
        check(!pads.receiveDisplayUrl(inv,"javascript:alert(1)",1_300_000_000L),"links cannot execute code");
        pads.close();displays.close();check(port.errors==0,"display link flow has no errors");
    }
    private static void writeSlot(InventoryState inv,UUID id,String[] tabs,int index)throws Exception{
        ByteBuffer packet=ByteBuffer.allocate(65536);packet.put((byte)0);VarInts.write(packet,1);packet.putShort((short)36);packet.put((byte)1);VarInts.write(packet,1200);packet.put((byte)1);packet.put(tag(id,tabs,index));packet.flip();inv.readVanillaContent(packet,true);
    }
    private static byte[] tag(UUID id,String[] tabs,int index)throws Exception{
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(bytes);out.writeByte(10);out.writeUTF("");
        out.writeByte(11);out.writeUTF("PadID");out.writeInt(4);out.writeLong(id.getMostSignificantBits());out.writeLong(id.getLeastSignificantBits());
        out.writeByte(9);out.writeUTF("PadTabs");out.writeByte(8);out.writeInt(tabs.length);for(String url:tabs)out.writeUTF(url);
        out.writeByte(3);out.writeUTF("PadTabIdx");out.writeInt(index);out.writeByte(8);out.writeUTF("PadURL");out.writeUTF(tabs[index]);out.writeByte(0);return bytes.toByteArray();
    }
    private static final class Fake implements MinePadController.Port {
        final Set<Integer> live=new HashSet<>();int shown=-1,creates,errors;
        public void create(int id,String url,boolean transparent){check(live.add(id),"duplicate browser id");creates++;}
        public void resize(int id,int width,int height){}
        public void navigate(int id,String url){}
        public void setVisible(int id,boolean value){}
        public void setFocused(int id,boolean value){}
        public void destroy(int id){check(live.remove(id),"destroy live browser");}
        public void show(int id,String url,int index,String[] tabs,boolean edit){shown=id;}
        public void hide(){shown=-1;}
        public void error(String value){errors++;System.out.println(value);}
    }
    private static void check(boolean value,String label){if(!value)throw new AssertionError(label);}
}
