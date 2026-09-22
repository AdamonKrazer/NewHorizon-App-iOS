package com.newhorizon.thinclient.world;

import com.newhorizon.thinclient.protocol.*;
import com.newhorizon.thinclient.inventory.InventoryState;
import java.nio.ByteBuffer;

public final class EffectsSelfTest {
    public static void run() throws Exception {
        PlayerEffects effects=new PlayerEffects();
        effects.update(effect(42,1,1,200),42);
        near(effects.movementSpeed(),.14,1e-7,"speed II");
        effects.update(effect(42,2,0,200),42);
        near(effects.movementSpeed(),.119,1e-7,"speed and slowness multiply");
        effects.update(effect(99,2,9,200),42);
        near(effects.movementSpeed(),.119,1e-7,"other entity ignored");
        effects.update(effect(42,8,1,-1),42); near(effects.jumpBoost(),.2,1e-9,"jump amplifier");
        effects.update(effect(42,28,0,200),42); near(effects.airborneVelocity(-2),-2.156,1e-9,"slow falling descent");
        near(effects.airborneVelocity(2),.392,1e-9,"slow falling preserves ascent gravity");
        effects.update(effect(42,25,1,200),42); near(effects.airborneVelocity(0),.392,1e-9,"levitation II");
        ByteBuffer remove=ByteBuffer.allocate(16); VarInts.write(remove,42);VarInts.write(remove,25);remove.flip(); effects.remove(remove,42);
        if(effects.level(25)!=0) throw new AssertionError("effect removal");
        effects.update(effect(42,9,0,0),42); if(effects.level(9)!=0) throw new AssertionError("effect expiry");
        if(!effects.encodeForUi().contains("8,1,-1,7")) throw new AssertionError("infinite duration UI");
        ByteBuffer attr=ByteBuffer.allocate(256); VarInts.write(attr,42);VarInts.write(attr,1);
        BinaryCodec.writeString(attr,"minecraft:generic.movement_speed",256);attr.putDouble(.1);VarInts.write(attr,3);
        modifier(attr,0,0,.2,2);modifier(attr,1,1,-.15,2);
        modifier(attr,0x662a6b8dda3e4c1cL,0x881396ea6097278dL,.3,2);
        attr.flip();effects.attributes(attr,42);near(effects.movementSpeed(),.102,1e-9,"attribute echo and sprint exclusion");
        effects.clear();near(effects.movementSpeed(),.1,1e-9,"respawn/reset");
        try { effects.update(ByteBuffer.wrap(new byte[]{42,9,0,100,7,1,10}),42); throw new AssertionError("truncated NBT accepted"); }
        catch(ProtocolException expected) { }

        InventoryState inventory=new InventoryState();
        ByteBuffer stack=ByteBuffer.allocate(128);stack.put((byte)0);VarInts.write(stack,1);stack.putShort((short)36);
        stack.put((byte)1);VarInts.write(stack,1000);stack.put((byte)1);
        byte[] tag={10,0,0,8,0,6,80,111,116,105,111,110,0,3,97,98,99,0}; stack.put(tag);stack.flip();
        inventory.readVanillaContent(stack,true);
        if(!inventory.encodeForUi().contains("1000\t0a0000080006506f74696f6e000361626300")) throw new AssertionError("server potion tag round trip");
        inventory.setCreativeSlot(36,"minecraft:splash_potion",1,1000,"0a000000");
        if(!inventory.encodeForUi().contains("minecraft:splash_potion\t\t1000\t0a000000")) throw new AssertionError("creative tag survives screen recreation");
        inventory.clear(); if(inventory.encodeForUi().contains("1000")) throw new AssertionError("inventory reset");
        System.out.println("Effect/inventory tests passed: attributes, physics, expiry, removal, NBT retention and bounds");
    }
    private static ByteBuffer effect(int entity,int id,int amp,int duration) {
        ByteBuffer out=ByteBuffer.allocate(32);VarInts.write(out,entity);VarInts.write(out,id);out.put((byte)amp);
        VarInts.write(out,duration);out.put((byte)7).put((byte)0);out.flip();return out;
    }
    private static void modifier(ByteBuffer out,long a,long b,double amount,int op) { out.putLong(a).putLong(b).putDouble(amount).put((byte)op); }
    private static void near(double actual,double expected,double error,String message) {
        if(Math.abs(actual-expected)>error) throw new AssertionError(message+": "+actual+" != "+expected);
    }
}
