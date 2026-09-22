package com.newhorizon.thinclient.render;

import com.newhorizon.thinclient.ThinClientRuntime;
import com.newhorizon.thinclient.display.BrowserPort;
import com.newhorizon.thinclient.protocol.*;
import com.newhorizon.thinclient.world.*;
import java.nio.ByteBuffer;
import java.lang.reflect.Proxy;

public final class DebugOverlaySelfTest {
    private static void check(boolean value,String label){if(!value)throw new AssertionError(label);}
    private static void string(ByteBuffer b,String value){BinaryCodec.writeString(b,value,32768);}
    public static void run()throws Exception {
        DebugCatalog c=DebugCatalog.get();check(c.block(1).equals("minecraft:stone")&&c.entity(15).equals("minecraft:chicken"),"official block and entity registry IDs");
        int oak=VanillaBlockTextures.defaultState("oak_log");check(c.properties(oak).equals("axis: y"),"real block-state properties");
        for(int state=0;state<24135;state++)check(!c.block(state).equals("unknown"),"all vanilla states identified");
        DebugNetworkState network=new DebugNetworkState();ByteBuffer b=ByteBuffer.allocate(4096);VarInts.write(b,2);string(b,"minecraft:block");VarInts.write(b,1);string(b,"minecraft:mineable/pickaxe");VarInts.write(b,1);VarInts.write(b,c.blockId(1));
        string(b,"minecraft:entity_type");VarInts.write(b,1);string(b,"test:poultry");VarInts.write(b,1);VarInts.write(b,15);b.flip();network.read(0x6e,b.duplicate());
        check(network.tags("minecraft:block",c.blockId(1)).contains("#minecraft:mineable/pickaxe")&&network.tags("minecraft:entity_type",15).contains("#test:poultry"),"authoritative server tags by registry");
        for(int n=0;n<b.limit();n++){ByteBuffer cut=b.duplicate();cut.limit(n);try{network.read(0x6e,cut);throw new AssertionError("truncated tags");}catch(ProtocolException expected){}check(network.tags("minecraft:block",c.blockId(1)).contains("pickaxe"),"tags atomic under truncation");}
        ByteBuffer clear=ByteBuffer.allocate(1);VarInts.write(clear,0);clear.flip();network.read(0x6e,clear);check(network.tags("minecraft:block",c.blockId(1)).isEmpty(),"datapack tag reload clears previous tags");
        try(WorldChunkStore w=FishingLeadsSelfTest.world()){
            w.applyVanillaBlockUpdate(2,1,4,VanillaBlockTextures.defaultState("water"));w.applyVanillaBlockUpdate(2,1,14,1);
            int[] block=new int[4],fluid=new int[4];DebugOverlay.targets(w,2.5,1.5,2.5,0,0,block,fluid);
            check(block[2]==14&&block[3]==1&&fluid[2]==4,"independent 20-block F3 ray keeps liquid and block targets");
        }
        check(DebugOverlay.localDifficulty(0,2400000)==0&&DebugOverlay.localDifficulty(3,0)==2.25f&&DebugOverlay.localDifficulty(3,2400000)==3,"vanilla remote-server difficulty formula");
        BrowserPort port=(BrowserPort)Proxy.newProxyInstance(BrowserPort.class.getClassLoader(),new Class[]{BrowserPort.class},(p,m,a)->null);
        try(ThinClientRuntime runtime=new ThinClientRuntime(port)){
            DebugOverlay overlay=new DebugOverlay();overlay.chart=true;overlay.pie=true;overlay.frames.add(20000000,1000000,2000000,3000000,14000000);
            String json=overlay.encode(runtime,System.nanoTime(),-1,-1,-1,1.62f,-181,0);
            check(json.contains("r.-1.-1.mca")&&json.contains("[15 15 15]")&&json.contains("Java:")&&json.contains("Targeted")==false&&json.contains("Allocation rate:")&&json.contains("20.0"),"negative chunk coordinates, system column and real frame samples");
            runtime.debug.reduced=true;json=overlay.encode(runtime,System.nanoTime(),-1,-1,-1,1.62f,-181,0);check(!json.contains("XYZ:")&&json.contains("Reduced debug info"),"respect server reduced-debug flag");
        }
        System.out.println("Debug overlay tests passed: 24135 states/properties, server tags/truncation/reload, independent fluid/block ray, negative regions, difficulty, frame telemetry and reduced-debug mode");
    }
}
