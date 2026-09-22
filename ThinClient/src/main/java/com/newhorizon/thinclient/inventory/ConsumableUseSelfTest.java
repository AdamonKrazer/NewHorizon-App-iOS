package com.newhorizon.thinclient.inventory;

import com.newhorizon.thinclient.world.*;
import com.newhorizon.thinclient.protocol.*;
import java.nio.ByteBuffer;

public final class ConsumableUseSelfTest {
    public static void run() {
        String[] regular={"APPLE","BAKED_POTATO","BEEF","BEETROOT","BEETROOT_SOUP","BREAD","CARROT",
                "CHICKEN","CHORUS_FRUIT","COD","COOKED_BEEF","COOKED_CHICKEN","COOKED_COD","COOKED_MUTTON",
                "COOKED_PORKCHOP","COOKED_RABBIT","COOKED_SALMON","COOKIE","ENCHANTED_GOLDEN_APPLE",
                "GOLDEN_APPLE","GOLDEN_CARROT","MELON_SLICE","MUSHROOM_STEW","MUTTON","POISONOUS_POTATO",
                "PORKCHOP","POTATO","PUFFERFISH","PUMPKIN_PIE","RABBIT","RABBIT_STEW","ROTTEN_FLESH",
                "SALMON","SPIDER_EYE","SUSPICIOUS_STEW","SWEET_BERRIES","GLOW_BERRIES","TROPICAL_FISH"};
        for(String food:regular) {
            check(ConsumableUse.durationTicks(food)==32,"normal duration: "+food);
            check(ProjectileKind.chargedItem(food)&&ProjectileKind.airUseItem(food),"must enter releasable use path: "+food);
            check(ConsumableUse.canStart(food,19,false),"hungry can eat "+food);
        }
        check(ConsumableUse.durationTicks("minecraft:dried_kelp")==16,"fast food");
        check(ConsumableUse.durationTicks("honey_bottle")==40,"honey duration");
        check(ConsumableUse.durationTicks("potion")==32&&ConsumableUse.drink("milk_bucket"),"drinks use held animation");
        check(!ConsumableUse.canStart("bread",20,false)&&ConsumableUse.canStart("bread",20,true),"full hunger vs creative");
        check(ConsumableUse.canStart("golden_apple",20,false)&&ConsumableUse.canStart("chorus_fruit",20,false)
                &&ConsumableUse.canStart("honey_bottle",20,false),"always usable exceptions");
        check(!ConsumableUse.canStart("other:bread",10,false)&&ConsumableUse.durationTicks("SPLASH_POTION")==0,"namespace and throwable exclusion");
        check(ConsumableUse.remaining(32,200_000_000)==28,"tap is not completed consumption");
        check(ConsumableUse.remaining(32,1_600_000_000)==0,"32 tick animation end");
        check(ConsumableUse.remaining(16,800_000_000)==0&&ConsumableUse.remaining(40,1_600_000_000)>0,"special durations");
        check(ConsumableUse.blend(32,32)==0&&ConsumableUse.blend(32,0)==1,"approach mouth endpoints");
        check(ConsumableUse.bob(32,30)==0&&ConsumableUse.bob(32,16)>.09f,"vanilla chew cutoff and motion");
        CombatState combat=new CombatState();long completed=combat.completedUses();
        combat.use(true,false);combat.use(false,false);
        check(combat.completedUses()==completed&&!combat.usingItem(),"release cancels without completing");
        combat.use(true,true);combat.completeUse();
        check(combat.completedUses()==completed+1&&!combat.usingItem(),"server completion ends main/offhand use");
        combat.clear();check(!combat.usingItem(),"world reset clears use");
        ByteBuffer wire=ByteBuffer.allocate(32);MinecraftPackets.writePlayerAction(wire,5,0,0,0,0,3);wire.flip();
        try {
            check(VarInts.read(wire)==MinecraftPacketIds.PLAY_SERVERBOUND_PLAYER_ACTION&&VarInts.read(wire)==5
                    &&wire.getLong()==0&&wire.get()==0&&VarInts.read(wire)==3&&!wire.hasRemaining(),"release packet roundtrip");
        } catch(ProtocolException e){throw new AssertionError(e);}
        WorldParticles particles=new WorldParticles();long now=System.nanoTime();
        particles.eat(758,0,65.62,0,0,0,5,now);
        WorldParticles.Particle[] out=new WorldParticles.Particle[16];for(int i=0;i<out.length;i++)out[i]=new WorldParticles.Particle();
        check(particles.snapshot(out,now,null,0,65,0)==5,"chew emits five crumbs");
        for(int i=0;i<5;i++)check(out[i].type==WorldParticles.ITEM&&out[i].z>.5&&out[i].y<65.62,"crumbs originate ahead of mouth");
        System.out.println("Consumable tests passed: all 40 foods, drinks, full hunger, held-use cancellation/completion, timing and crumbs");
    }
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
}
