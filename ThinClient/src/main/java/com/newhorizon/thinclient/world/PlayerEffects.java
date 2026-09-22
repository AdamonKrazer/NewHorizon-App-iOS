package com.newhorizon.thinclient.world;

import com.newhorizon.thinclient.protocol.*;
import java.nio.ByteBuffer;
import java.util.Arrays;

/** Fixed-capacity projection of the server's effects on the local player. */
public final class PlayerEffects {
    public static final int SPEED=1,SLOWNESS=2,HASTE=3,FATIGUE=4,JUMP=8,NAUSEA=9,
            FIRE_RESISTANCE=12,INVISIBILITY=14,BLINDNESS=15,NIGHT_VISION=16,
            LEVITATION=25,SLOW_FALLING=28,DOLPHINS_GRACE=30,DARKNESS=33;
    private final int[] amplifiers=new int[256],flags=new int[256];
    private final long[] expires=new long[256];
    private double movementAttribute=Double.NaN;
    private int diagnosticCount;

    public synchronized void update(ByteBuffer in,int localPlayer) throws ProtocolException {
        int entity=VarInts.read(in),effect=VarInts.read(in);
        BinaryCodec.require(in,1); int amplifier=in.get()&255;
        int duration=VarInts.read(in); BinaryCodec.require(in,2);
        int flag=in.get()&255; boolean factor=in.get()!=0;
        if(factor) NbtSkipper.skipRoot(in);
        if(effect<0 || duration< -1 || in.hasRemaining()) throw new ProtocolException("Invalid mob effect");
        if(entity!=localPlayer || effect>=expires.length) return;
        amplifiers[effect]=amplifier; flags[effect]=flag;
        expires[effect]=duration<0?Long.MAX_VALUE:System.nanoTime()+duration*50_000_000L;
        if(diagnosticCount++<32) System.out.println("[NH-THIN-EFFECT] add id="+effect+" amplifier="+amplifier+" ticks="+duration);
    }
    public synchronized void remove(ByteBuffer in,int localPlayer) throws ProtocolException {
        int entity=VarInts.read(in),effect=VarInts.read(in);
        if(effect<0 || in.hasRemaining()) throw new ProtocolException("Invalid effect removal");
        if(entity==localPlayer && effect<expires.length) {
            expires[effect]=0;
            if(diagnosticCount++<32) System.out.println("[NH-THIN-EFFECT] remove id="+effect);
        }
    }
    public synchronized void attributes(ByteBuffer in,int localPlayer) throws ProtocolException {
        attributes(in,localPlayer,null);
    }
    public synchronized void attributes(ByteBuffer in,int localPlayer,CombatState combat) throws ProtocolException {
        attributes(in,localPlayer,combat,null);
    }
    public synchronized void attributes(ByteBuffer in,int localPlayer,CombatState combat,EntityTracker entities) throws ProtocolException {
        int entity=VarInts.read(in),count=VarInts.read(in);
        if(count<0 || count>256) throw new ProtocolException("Attribute count outside limit");
        for(int i=0;i<count;i++) {
            String key=BinaryCodec.readString(in,256); BinaryCodec.require(in,8); double base=in.getDouble();
            int modifiers=VarInts.read(in);
            if(!Double.isFinite(base) || modifiers<0 || modifiers>256) throw new ProtocolException("Invalid attribute");
            double add=0,multiplyBase=0,multiplyTotal=1;
            for(int m=0;m<modifiers;m++) {
                BinaryCodec.require(in,25); long high=in.getLong(),low=in.getLong();
                double amount=in.getDouble(); int operation=in.get()&255;
                if(!Double.isFinite(amount) || operation>2) throw new ProtocolException("Invalid attribute modifier");
                // Sprint is applied immediately from local input, not twice via server echo.
                if(high==0x662a6b8dda3e4c1cL && low==0x881396ea6097278dL) continue;
                if(operation==0) add+=amount;
                else if(operation==1) multiplyBase+=amount;
                else multiplyTotal*=1+amount;
            }
            if(entity==localPlayer && (key.equals("minecraft:generic.movement_speed")
                    || key.equals("generic.movement_speed")))
                movementAttribute=Math.max(0,Math.min(1024,(base+add)*(1+multiplyBase)*multiplyTotal));
            if(entities!=null)entities.attribute(entity,key,(base+add)*(1+multiplyBase)*multiplyTotal);
            if(entity==localPlayer && combat!=null) combat.attribute(key,(base+add)*(1+multiplyBase)*multiplyTotal);
        }
        if(in.hasRemaining()) throw new ProtocolException("Trailing attribute bytes");
    }
    public synchronized int level(int effect) {
        return effect>=0 && effect<expires.length && expires[effect]>System.nanoTime()?amplifiers[effect]+1:0;
    }
    public synchronized double movementSpeed() {
        if(!Double.isNaN(movementAttribute)) return movementAttribute;
        return .1*(1+(double).2f*level(SPEED))*Math.max(0,1-(double).15f*level(SLOWNESS));
    }
    public double jumpBoost() { return .1*level(JUMP); }
    public double gravity(double velocity) { return velocity<=0 && level(SLOW_FALLING)>0?.01:.08; }
    public double airborneVelocity(double velocity) {
        int levitation=level(LEVITATION);
        return (levitation>0?velocity+(.05*levitation*20-velocity)*.2:velocity-gravity(velocity)*20)*.98;
    }
    public synchronized String encodeForUi() {
        StringBuilder out=new StringBuilder(512); long now=System.nanoTime();
        for(int id=1;id<=33;id++) if(expires[id]>now) {
            long ticks=expires[id]==Long.MAX_VALUE?-1:(expires[id]-now+49_999_999)/50_000_000;
            out.append(id).append(',').append(amplifiers[id]).append(',').append(ticks).append(',').append(flags[id]).append('\n');
        }
        return out.toString();
    }
    public synchronized void clear() { Arrays.fill(expires,0); movementAttribute=Double.NaN; diagnosticCount=0; }
}
