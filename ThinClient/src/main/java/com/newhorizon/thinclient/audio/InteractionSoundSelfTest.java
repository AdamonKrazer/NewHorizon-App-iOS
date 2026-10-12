package com.newhorizon.thinclient.audio;

import com.newhorizon.thinclient.memory.MemoryBudget;
import com.newhorizon.thinclient.protocol.VarInts;
import com.newhorizon.thinclient.world.*;
import java.nio.ByteBuffer;

/** Replays authoritative state changes through the actual local sound queue. */
public final class InteractionSoundSelfTest {
    public static void run() throws Exception {
        String[] woods = {"oak","spruce","birch","jungle","acacia","dark_oak","mangrove","cherry","bamboo","crimson","warped"};
        try (WorldChunkStore world = new WorldChunkStore(MemoryBudget.lowRamDefaults(), 1, 16)) {
            world.handle(new WorldMessage.Reset("minecraft:overworld", 0, 64));
            world.applyVanillaChunkWithLight(0, 0, air(), dark());
            SoundEventQueue queue = new SoundEventQueue(32);
            LocalPlayerSounds local = new LocalPlayerSounds(world, queue);
            long now = 0;
            for (String wood : woods) for (String kind : new String[]{"door","trapdoor","fence_gate"}) {
                String name = "minecraft:" + wood + "_" + kind;
                int closed = find(name, "open: false", "powered: false");
                int open = counterpart(closed, "open: false", "open: true");
                String family = wood.equals("cherry") ? "cherry_wood_" : wood.equals("bamboo") ? "bamboo_wood_"
                        : wood.equals("crimson") || wood.equals("warped") ? "nether_wood_" : kind.equals("fence_gate") ? "" : "wooden_";
                String sound = "minecraft:block." + family + kind;
                world.applyVanillaBlockUpdate(4, 4, 4, closed);
                local.expectUse(4,4,4,1,name,"AIR",false,now);
                local.update(now+1); silence(queue, "no speculative placement/opening");
                world.applyVanillaBlockUpdate(4,4,4,open);
                local.update(now+2); event(queue,sound+".open",1,0.9f,1);
                local.update(now+3); silence(queue,"one sample for the consumed use");
                local.expectUse(4,4,4,1,name,"AIR",false,now+4);
                world.applyVanillaBlockUpdate(4,4,4,closed);
                local.update(now+5); event(queue,sound+".close",1,0.9f,1);
                // Sneaking with both hands empty still allows normal block interaction.
                local.expectUse(4,4,4,1,"AIR","AIR",true,now+6);
                world.applyVanillaBlockUpdate(4,4,4,open);
                local.update(now+7); event(queue,sound+".open",1,0.9f,1);
                now += 100;
            }
            int closed=find("minecraft:oak_door","open: false","powered: false");
            int open=counterpart(closed,"open: false","open: true");
            world.applyVanillaBlockUpdate(4,4,4,closed);
            local.expectUse(4,4,4,1,"OAK_DOOR","AIR",false,now);
            // A neighboring redstone/property refresh is not opening.
            world.applyVanillaBlockUpdate(4,4,4,counterpart(closed,"powered: false","powered: true"));
            local.update(now+1); silence(queue,"power alone is silent");
            world.applyVanillaBlockUpdate(4,4,4,open);
            local.update(now+2); event(queue,"minecraft:block.wooden_door.open",1,.9f,1);
            local.expectUse(4,4,4,1,"OAK_DOOR","AIR",true,now+3);
            world.applyVanillaBlockUpdate(4,4,4,closed);
            local.update(now+4); silence(queue,"sneak-held block does not predict opening or placement on state change");
            // Real placement beside the clicked block still produces its SoundType sample.
            world.applyVanillaBlockUpdate(4,5,4,closed);
            local.update(now+5); event(queue,"minecraft:block.wood.place",1,.8f,.8f);
            local.expectUse(4,4,4,1,"AIR","AIR",false,now+6);
            local.update(now+2_000_000_007L);
            world.applyVanillaBlockUpdate(4,4,4,open);
            local.update(now+2_000_000_008L); silence(queue,"expired/rejected action cannot sound later");
            local.expectUse(4,4,4,1,"AIR","AIR",false,now+2_000_000_009L);
            queue.clear();
            world.applyVanillaBlockUpdate(4,4,4,closed);
            local.update(now+2_000_000_010L); silence(queue,"world audio reset cancels pending interactions");
            // A replacing two-part door must not emit two place sounds.
            world.applyVanillaBlockUpdate(7,4,7,0);world.applyVanillaBlockUpdate(7,5,7,0);
            local.expectPlace(7,4,7,1,"OAK_DOOR",now+3_000_000_000L);
            world.applyVanillaBlockUpdate(7,4,7,closed);world.applyVanillaBlockUpdate(7,5,7,closed);
            local.update(now+3_000_000_001L);event(queue,"minecraft:block.wood.place",1,.8f,.8f);
        }
        check(!BlockInteractionSounds.canOpenByHand(find("minecraft:iron_door")),"iron doors remain server/redstone controlled");
        check(!BlockInteractionSounds.canOpenByHand(find("minecraft:iron_trapdoor")),"iron trapdoors remain server/redstone controlled");
        for(String[] fixture:new String[][]{{"oak_slab","type: bottom","type: double"},{"snow","layers: 1","layers: 2"},
                {"candle","candles: 1","candles: 2"},{"red_candle","candles: 1","candles: 2"},
                {"sea_pickle","pickles: 1","pickles: 2"},{"turtle_egg","eggs: 1","eggs: 2"},{"pink_petals","flower_amount: 1","flower_amount: 2"}}) {
            String name="minecraft:"+fixture[0]; int a=find(name,fixture[1]), b=counterpart(a,fixture[1],fixture[2]);
            check(BlockInteractionSounds.placed(a,b,name),"stack increase: "+name);
            check(!BlockInteractionSounds.placed(b,a,name),"stack decrease is not placement: "+name);
        }
        int candle=find("minecraft:candle","lit: false");
        check(!BlockInteractionSounds.placed(candle,counterpart(candle,"lit: false","lit: true"),"minecraft:candle"),"lighting is not placing a candle");
        System.out.println("Interaction sound tests passed: 33 door/trapdoor/gate variants, same held item, empty hands, sneak, placement, rejection, reset, stacking");
    }
    private static int find(String name,String... props) {
        DebugCatalog c=DebugCatalog.get();
        for(int i=0;i<24135;i++)if(c.block(i).equals(name)) {
            boolean ok=true;String p=c.properties(i);
            for(String prop:props)if(!p.contains(prop))ok=false;
            if(ok)return i;
        }
        throw new AssertionError("No state: "+name+java.util.Arrays.toString(props));
    }
    private static int counterpart(int state,String from,String to) {
        DebugCatalog c=DebugCatalog.get();String wanted=c.properties(state).replace(from,to);
        for(int i=0;i<24135;i++)if(c.block(i).equals(c.block(state))&&c.properties(i).equals(wanted))return i;
        throw new AssertionError("No counterpart");
    }
    private static void event(SoundEventQueue q,String name,float volume,float minPitch,float maxPitch) {
        SoundEventQueue.Event e=new SoundEventQueue.Event();
        check(q.poll(e)&&name.equals(e.soundName),"expected "+name+", got "+e.soundName);
        check(e.category==SoundEventQueue.BLOCKS&&e.volume==volume&&e.pitch>=minPitch&&e.pitch<=maxPitch,"vanilla sound parameters");
        check(VanillaSoundCatalog.load().choose(name,1)!=null,"original sound asset exists");
        silence(q,"no duplicate sample");
    }
    private static void silence(SoundEventQueue q,String label) {check(q.size()==0,label);}
    private static void check(boolean ok,String label){if(!ok)throw new AssertionError(label);}
    private static ByteBuffer air(){ByteBuffer b=ByteBuffer.allocate(48);for(int i=0;i<4;i++){b.putShort((short)0).put((byte)0);VarInts.write(b,0);VarInts.write(b,0);b.put((byte)0);VarInts.write(b,0);VarInts.write(b,0);}b.flip();return b;}
    private static ByteBuffer dark(){ByteBuffer b=ByteBuffer.allocate(32);VarInts.write(b,0);VarInts.write(b,0);VarInts.write(b,0);VarInts.write(b,1);b.putLong(63);VarInts.write(b,1);b.putLong(63);VarInts.write(b,0);VarInts.write(b,0);b.flip();return b;}
}
